package org.zstack.header.vm;

import org.zstack.header.message.NeedReplyMessage;

/**
 * Created by LiangHanYu on 2022/6/22 17:12
 */
public class SetVmStaticIpMsg extends NeedReplyMessage implements VmInstanceMessage {
    private String vmInstanceUuid;
    private String l3NetworkUuid;
    private String ip;
    private String ip6;

    @Override
    public String getVmInstanceUuid() {
        return vmInstanceUuid;
    }

    public void setVmInstanceUuid(String vmInstanceUuid) {
        this.vmInstanceUuid = vmInstanceUuid;
    }

    public String getL3NetworkUuid() {
        return l3NetworkUuid;
    }

    public void setL3NetworkUuid(String l3NetworkUuid) {
        this.l3NetworkUuid = l3NetworkUuid;
    }

    public String getIp() {
        return ip;
    }

    public void setIp(String ip) {
        this.ip = ip;
    }

    public String getIp6() {
        return ip6;
    }

    public void setIp6(String ip6) {
        this.ip6 = ip6;
    }
}
