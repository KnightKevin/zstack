package org.zstack.network.service.virtualrouter;

import org.springframework.beans.factory.annotation.Autowire;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Configurable;
import org.zstack.appliancevm.*;
import org.zstack.appliancevm.ApplianceVmConstant.Params;
import org.zstack.core.cloudbus.CloudBusCallBack;
import org.zstack.core.componentloader.PluginExtension;
import org.zstack.core.db.Q;
import org.zstack.core.db.SimpleQuery;
import org.zstack.core.db.SimpleQuery.Op;
import org.zstack.core.errorcode.ErrorFacade;
import org.zstack.core.thread.ChainTask;
import org.zstack.core.thread.SyncTaskChain;
import org.zstack.core.timeout.ApiTimeoutManager;
import org.zstack.header.core.Completion;
import org.zstack.header.core.workflow.*;
import org.zstack.header.errorcode.ErrorCode;
import org.zstack.header.errorcode.OperationFailureException;
import org.zstack.header.errorcode.SysErrors;
import org.zstack.header.message.APIMessage;
import org.zstack.header.message.Message;
import org.zstack.header.message.MessageReply;
import org.zstack.header.network.l2.L2Network;
import org.zstack.header.network.l2.L2NetworkGetVniExtensionPoint;
import org.zstack.header.network.l2.L2NetworkVO;
import org.zstack.header.network.l2.L2NetworkVO_;
import org.zstack.header.network.l3.L3Network;
import org.zstack.header.network.l3.L3NetworkCategory;
import org.zstack.header.network.l3.L3NetworkVO;
import org.zstack.header.network.l3.L3NetworkVO_;
import org.zstack.header.rest.JsonAsyncRESTCallback;
import org.zstack.header.rest.RESTFacade;
import org.zstack.header.vm.*;
import org.zstack.network.l3.L3NetworkSystemTags;
import org.zstack.network.service.virtualrouter.VirtualRouterCommands.PingCmd;
import org.zstack.network.service.virtualrouter.VirtualRouterCommands.PingRsp;
import org.zstack.network.service.virtualrouter.VirtualRouterConstant.Param;

import static org.zstack.core.Platform.operr;
import static org.zstack.network.service.virtualrouter.VirtualRouterNicMetaData.ADDITIONAL_PUBLIC_NIC_MASK;
import static org.zstack.network.service.virtualrouter.VirtualRouterNicMetaData.GUEST_NIC_MASK;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 */
@Configurable(preConstruction = true, autowire = Autowire.BY_TYPE)
public class VirtualRouter extends ApplianceVmBase {

    static {
        allowedOperations.addState(VmInstanceState.Running, APIReconnectVirtualRouterMsg.class.getName());
        allowedOperations.addState(VmInstanceState.Running, ReconnectVirtualRouterVmMsg.class.getName());
    }

    @Autowired
    protected VirtualRouterManager vrMgr;
    @Autowired
    protected RESTFacade restf;
    @Autowired
    protected ErrorFacade errf;
    @Autowired
    protected ApiTimeoutManager apiTimeoutManager;

    protected VirtualRouterVmInventory vr;

    public VirtualRouter(ApplianceVmVO vo) {
        super(vo);
    }

    public VirtualRouter(VirtualRouterVmVO vo) {
        super(vo);
        vr = new VirtualRouterVmInventory(vo);
    }

    @Override
    protected VmInstanceInventory getSelfInventory() {
        return VirtualRouterVmInventory.valueOf(getSelf());
    }

    @Override
    protected List<Flow> getPostCreateFlows() {
        return vrMgr.getPostCreateFlows();
    }

    @Override
    protected List<Flow> getPostStartFlows() {
        return vrMgr.getPostStartFlows();
    }

    @Override
    protected List<Flow> getPostStopFlows() {
        return vrMgr.getPostStopFlows();
    }

    @Override
    protected List<Flow> getPostRebootFlows() {
        return vrMgr.getPostRebootFlows();
    }

    @Override
    protected List<Flow> getPostDestroyFlows() {
        return vrMgr.getPostDestroyFlows();
    }

    @Override
    protected List<Flow> getPostMigrateFlows() {
        return vrMgr.getPostMigrateFlows();
    }

    protected FlowChain getReconnectChain() {
        return vrMgr.getReconnectFlowChain();
    }

    @Override
    protected void handleApiMessage(APIMessage msg) {
        if (msg instanceof APIReconnectVirtualRouterMsg) {
            handle((APIReconnectVirtualRouterMsg) msg);
        } else {
            super.handleApiMessage(msg);
        }
    }

    @Override
    protected void handleLocalMessage(Message msg) {
        if (msg instanceof VirtualRouterAsyncHttpCallMsg) {
            handle((VirtualRouterAsyncHttpCallMsg) msg);
        } else if (msg instanceof ReconnectVirtualRouterVmMsg) {
            handle((ReconnectVirtualRouterVmMsg) msg);
        } else if (msg instanceof PingVirtualRouterVmMsg) {
            handle((PingVirtualRouterVmMsg) msg);
        } else {
            super.handleLocalMessage(msg);
        }
    }

    private void handle(final PingVirtualRouterVmMsg msg) {
        thdf.chainSubmit(new ChainTask(msg) {
            @Override
            public String getSyncSignature() {
                return syncThreadName;
            }

            @Override
            public void run(final SyncTaskChain chain) {
                final PingVirtualRouterVmReply reply = new PingVirtualRouterVmReply();
                if (VmInstanceState.Running != self.getState() || ApplianceVmStatus.Connecting == getSelf().getStatus()) {
                    reply.setDoReconnect(false);
                    bus.reply(msg, reply);
                    chain.next();
                    return;
                }

                PingCmd cmd = new PingCmd();
                cmd.setUuid(self.getUuid());
                restf.asyncJsonPost(buildUrl(vr.getManagementNic().getIp(), VirtualRouterConstant.VR_PING), cmd, new JsonAsyncRESTCallback<PingRsp>(msg, chain) {
                    @Override
                    public void fail(ErrorCode err) {
                        reply.setDoReconnect(true);
                        reply.setConnected(false);
                        logger.warn(String.format("failed to ping the virtual router vm[uuid:%s], %s. We will reconnect it soon", self.getUuid(), reply.getError()));
                        bus.reply(msg, reply);
                        chain.next();
                    }

                    @Override
                    public void success(PingRsp ret) {
                        reply.setDoReconnect(true);
                        if (!ret.isSuccess()) {
                            logger.warn(String.format("failed to ping the virtual router vm[uuid:%s], %s. We will reconnect it soon", self.getUuid(), ret.getError()));
                            reply.setConnected(false);
                        } else {
                            boolean connected = self.getUuid().equals(ret.getUuid());
                            if (!connected) {
                                logger.warn(String.format("a signature lost on the virtual router vm[uuid:%s] changed, it's probably caused by the agent restart. We will issue a reconnect soon", self.getUuid()));
                            }
                            reply.setConnected(connected);
                        }
                        bus.reply(msg, reply);
                        chain.next();
                    }

                    @Override
                    public Class<PingRsp> getReturnClass() {
                        return PingRsp.class;
                    }
                });
            }

            @Override
            public String getName() {
                return "ping-virtual-router";
            }
        });


    }

    private void handle(final ReconnectVirtualRouterVmMsg msg) {
        thdf.chainSubmit(new ChainTask(msg) {
            @Override
            public String getSyncSignature() {
                return syncThreadName;
            }

            @Override
            public void run(final SyncTaskChain chain) {
                final ReconnectVirtualRouterVmReply reply = new ReconnectVirtualRouterVmReply();

                refreshVO();
                ErrorCode allowed = validateOperationByState(msg, self.getState(), SysErrors.OPERATION_ERROR);
                if (allowed != null) {
                    reply.setError(allowed);
                    bus.reply(msg, reply);
                    chain.next();
                    return;
                }

                reconnect(new Completion(msg, chain) {
                    @Override
                    public void success() {
                        bus.reply(msg, reply);
                        chain.next();
                    }

                    @Override
                    public void fail(ErrorCode errorCode) {
                        reply.setError(errorCode);
                        bus.reply(msg, reply);
                        chain.next();
                    }
                });
            }

            @Override
            public String getName() {
                return String.format("reconnect-virtual-router-%s", self.getUuid());
            }
        });
    }

    protected String buildUrl(String mgmtIp, String path) {
        return vrMgr.buildUrl(mgmtIp, path);
    }

    private void handle(final VirtualRouterAsyncHttpCallMsg msg) {
        thdf.chainSubmit(new ChainTask(msg) {
            @Override
            public String getSyncSignature() {
                return String.format("%s-commands", syncThreadName);
            }

            @Override
            public void run(final SyncTaskChain chain) {
                refreshVO();

                final VirtualRouterAsyncHttpCallReply reply = new VirtualRouterAsyncHttpCallReply();
                if (msg.isCheckStatus() && getSelf().getState() != VmInstanceState.Running) {
                    throw new OperationFailureException(operr("the virtual router[name:%s, uuid:%s, current state:%s] is not running," +
                                    "and cannot perform required operation. Please retry your operation later once it is running", self.getName(), self.getUuid(), self.getState()));
                }

                if (msg.isCheckStatus() && getSelf().getStatus() != ApplianceVmStatus.Connected) {
                    throw new OperationFailureException(operr("virtual router[uuid:%s] is in status of %s that cannot make http call to %s",
                            self.getUuid(), getSelf().getStatus(), msg.getPath()));
                }

                restf.asyncJsonPost(buildUrl(vr.getManagementNic().getIp(), msg.getPath()), msg.getCommand(), new JsonAsyncRESTCallback<LinkedHashMap>(msg, chain) {
                    @Override
                    public void fail(ErrorCode err) {
                        reply.setError(err);
                        bus.reply(msg, reply);
                        chain.next();
                    }

                    @Override
                    public void success(LinkedHashMap ret) {
                        reply.setResponse(ret);
                        bus.reply(msg, reply);
                        chain.next();
                    }

                    @Override
                    public Class<LinkedHashMap> getReturnClass() {
                        return LinkedHashMap.class;
                    }
                }, TimeUnit.SECONDS, msg.getCommandTimeout());
            }

            @Override
            protected int getSyncLevel() {
                return vrMgr.getParallelismDegree(self.getUuid());
            }

            @Override
            public String getName() {
                return getSyncSignature();
            }
        });
    }

    private void handle(final APIReconnectVirtualRouterMsg msg) {
        thdf.chainSubmit(new ChainTask(msg) {
            @Override
            public String getSyncSignature() {
                return syncThreadName;
            }

            @Override
            public void run(final SyncTaskChain chain) {
                final APIReconnectVirtualRouterEvent evt = new APIReconnectVirtualRouterEvent(msg.getId());

                refreshVO();
                ErrorCode allowed = validateOperationByState(msg, self.getState(), SysErrors.OPERATION_ERROR);
                if (allowed != null) {
                    evt.setError(allowed);
                    bus.publish(evt);
                    chain.next();
                    return;
                }

                reconnect(new Completion(msg, chain) {
                    @Override
                    public void success() {
                        evt.setInventory((ApplianceVmInventory) getSelfInventory());
                        bus.publish(evt);
                        chain.next();
                    }

                    @Override
                    public void fail(ErrorCode errorCode) {
                        evt.setError(errorCode);
                        bus.publish(evt);
                        chain.next();
                    }
                });
            }

            @Override
            public String getName() {
                return String.format("reconnect-virtual-router-%s", self.getUuid());
            }
        });
    }

    private void reconnect(final Completion completion) {
        FlowChain chain = getReconnectChain();
        chain.setName(String.format("reconnect-virtual-router-%s", self.getUuid()));
        chain.getData().put(VirtualRouterConstant.Param.VR.toString(), vr);
        chain.getData().put(Param.IS_RECONNECT.toString(), Boolean.TRUE.toString());
        chain.getData().put(Params.isReconnect.toString(), Boolean.TRUE.toString());
        chain.getData().put(Params.managementNicIp.toString(), vr.getManagementNic().getIp());
        chain.getData().put(Params.applianceVmUuid.toString(), self.getUuid());

        SimpleQuery<ApplianceVmFirewallRuleVO> q = dbf.createQuery(ApplianceVmFirewallRuleVO.class);
        q.add(ApplianceVmFirewallRuleVO_.applianceVmUuid, Op.EQ, getSelf().getUuid());
        List<ApplianceVmFirewallRuleVO> vos = q.list();
        List<ApplianceVmFirewallRuleInventory> rules = ApplianceVmFirewallRuleInventory.valueOf(vos);
        chain.getData().put(ApplianceVmConstant.Params.applianceVmFirewallRules.toString(), rules);

        chain.insert(new Flow() {
            String __name__ = "change-appliancevm-status-to-connecting";

            @Override
            public void run(FlowTrigger trigger, Map data) {
                getSelf().setStatus(ApplianceVmStatus.Connecting);
                self = dbf.updateAndRefresh(self);
                trigger.next();
            }

            @Override
            public void rollback(FlowRollback trigger, Map data) {
                self = dbf.reload(self);
                getSelf().setStatus(ApplianceVmStatus.Disconnected);
                self = dbf.updateAndRefresh(self);
                trigger.rollback();
            }
        }).then(new NoRollbackFlow() {
            String __name__ = "change-appliancevm-status-to-connected";

            @Override
            public void run(FlowTrigger trigger, Map data) {
                getSelf().setStatus(ApplianceVmStatus.Connected);
                self = dbf.updateAndRefresh(self);
                trigger.next();
            }
        }).done(new FlowDoneHandler(completion) {
            @Override
            public void handle(Map data) {
                self = dbf.reload(self);
                completion.success();
            }
        }).error(new FlowErrorHandler(completion) {
            @Override
            public void handle(ErrorCode errCode, Map data) {
                completion.fail(errCode);
            }
        }).start();
    }

    @Override
    protected void afterAttachNic(VmNicInventory nicInventory, Completion completion) {
        VmNicVO vo = Q.New(VmNicVO.class).eq(VmNicVO_.uuid, nicInventory.getUuid()).find();
        L3NetworkVO l3NetworkVO = Q.New(L3NetworkVO.class).eq(L3NetworkVO_.uuid, vo.getL3NetworkUuid()).find();

        if (l3NetworkVO.getCategory().equals(L3NetworkCategory.Private)) {
            vo.setMetaData(GUEST_NIC_MASK.toString());
        } else {
            vo.setMetaData(ADDITIONAL_PUBLIC_NIC_MASK.toString());
        }
        dbf.updateAndRefresh(vo);
        logger.debug(String.format("updated metadata of vmnic[uuid: %s]", vo.getUuid()));

        VirtualRouterCommands.ConfigureNicCmd cmd = new VirtualRouterCommands.ConfigureNicCmd();
        VirtualRouterCommands.NicInfo info = new VirtualRouterCommands.NicInfo();
        info.setIp(nicInventory.getIp());
        info.setDefaultRoute(false);
        info.setGateway(nicInventory.getGateway());
        info.setMac(nicInventory.getMac());
        info.setNetmask(nicInventory.getNetmask());

        L2NetworkVO l2NetworkVO = Q.New(L2NetworkVO.class).eq(L2NetworkVO_.uuid, l3NetworkVO.getL2NetworkUuid()).find();
        info.setCategoryy(l3NetworkVO.getCategory().toString());
        info.setL2type(l2NetworkVO.getType());
        for (L2NetworkGetVniExtensionPoint ext : pluginRgty.getExtensionList(L2NetworkGetVniExtensionPoint.class)) {
            if (ext.getL2NetworkVniType().equals(l2NetworkVO.getType())) {
                info.setVni(ext.getL2NetworkVni(l2NetworkVO.getUuid()));
            }
        }
        cmd.setNics(Arrays.asList(info));

        VirtualRouterAsyncHttpCallMsg cmsg = new VirtualRouterAsyncHttpCallMsg();
        cmsg.setCommand(cmd);
        cmsg.setCommandTimeout(apiTimeoutManager.getTimeout(cmd.getClass(), "30m"));
        cmsg.setPath(VirtualRouterConstant.VR_CONFIGURE_NIC_PATH);
        cmsg.setVmInstanceUuid(vr.getUuid());
        cmsg.setCheckStatus(true);
        bus.makeTargetServiceIdByResourceUuid(cmsg, VmInstanceConstant.SERVICE_ID, vr.getUuid());
        bus.send(cmsg, new CloudBusCallBack(completion) {
            @Override
            public void run(MessageReply reply) {
                if (!reply.isSuccess()) {
                    completion.fail(reply.getError());
                    return;
                }

                VirtualRouterAsyncHttpCallReply re = reply.castReply();
                VirtualRouterCommands.ConfigureNicRsp rsp = re.toResponse(VirtualRouterCommands.ConfigureNicRsp.class);
                if (rsp.isSuccess()) {

                    logger.debug(String.format("successfully add nic[%s] to virtual router vm[uuid:%s, ip:%s]",info, vr.getUuid(), vr.getManagementNic()
                            .getIp()));
                    completion.success();
                } else {
                    ErrorCode err = operr("unable to add nic[%s] to virtual router vm[uuid:%s ip:%s], because %s",
                            info, vr.getUuid(), vr.getManagementNic().getIp(), rsp.getError());
                    completion.fail(err);
                }
            }
        });
    }

    @Override
    protected void beforeDetachNic(VmNicInventory nicInventory, Completion completion) {
        VirtualRouterCommands.RemoveNicCmd cmd = new VirtualRouterCommands.RemoveNicCmd();
        VirtualRouterCommands.NicInfo info = new VirtualRouterCommands.NicInfo();
        info.setIp(nicInventory.getIp());
        info.setDefaultRoute(false);
        info.setGateway(nicInventory.getGateway());
        info.setMac(nicInventory.getMac());
        info.setNetmask(nicInventory.getNetmask());
        cmd.setNics(Arrays.asList(info));

        VirtualRouterAsyncHttpCallMsg cmsg = new VirtualRouterAsyncHttpCallMsg();
        cmsg.setCommand(cmd);
        cmsg.setCommandTimeout(apiTimeoutManager.getTimeout(cmd.getClass(), "30m"));
        cmsg.setPath(VirtualRouterConstant.VR_REMOVE_NIC_PATH);
        cmsg.setVmInstanceUuid(vr.getUuid());
        cmsg.setCheckStatus(true);
        bus.makeTargetServiceIdByResourceUuid(cmsg, VmInstanceConstant.SERVICE_ID, vr.getUuid());
        bus.send(cmsg, new CloudBusCallBack(completion) {
            @Override
            public void run(MessageReply reply) {
                if (!reply.isSuccess()) {
                    completion.fail(reply.getError());
                    return;
                }

                VirtualRouterAsyncHttpCallReply re = reply.castReply();
                VirtualRouterCommands.RemoveNicRsp rsp = re.toResponse(VirtualRouterCommands.RemoveNicRsp.class);
                if (rsp.isSuccess()) {
                    logger.debug(String.format("successfully detach nic[%s] from virtual router vm[uuid:%s, ip:%s]",info, vr.getUuid(), vr.getManagementNic()
                            .getIp()));
                    completion.success();
                } else {
                    ErrorCode err = operr("unable to detach nic[%s] from virtual router vm[uuid:%s ip:%s], because %s",
                            info, vr.getUuid(), vr.getManagementNic().getIp(), rsp.getError());
                    completion.fail(err);
                }
            }
        });
    }

}
