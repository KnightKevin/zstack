package org.zstack.core.cloudbus;

import org.apache.logging.log4j.ThreadContext;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.*;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClientException;
import org.zstack.core.CoreGlobalProperty;
import org.zstack.core.MessageCommandRecorder;
import org.zstack.core.Platform;
import org.zstack.core.asyncbatch.While;
import org.zstack.core.componentloader.PluginRegistry;
import org.zstack.core.errorcode.ErrorFacade;
import org.zstack.core.retry.Retry;
import org.zstack.core.retry.RetryCondition;
import org.zstack.core.thread.AsyncThread;
import org.zstack.core.thread.SyncTask;
import org.zstack.core.thread.ThreadFacade;
import org.zstack.core.thread.ThreadFacadeImpl;
import org.zstack.core.timeout.ApiTimeoutManager;
import org.zstack.header.Constants;
import org.zstack.header.Service;
import org.zstack.header.apimediator.StopRoutingException;
import org.zstack.header.core.ExceptionSafe;
import org.zstack.header.core.FutureReturnValueCompletion;
import org.zstack.header.core.NoErrorCompletion;
import org.zstack.header.core.NopeNoErrorCompletion;
import org.zstack.header.errorcode.ErrorCode;
import org.zstack.header.errorcode.OperationFailureException;
import org.zstack.header.errorcode.SysErrors;
import org.zstack.header.exception.CloudConfigureFailException;
import org.zstack.header.exception.CloudRuntimeException;
import org.zstack.header.message.*;
import org.zstack.header.rest.RESTFacade;
import org.zstack.header.rest.TimeoutRestTemplate;
import org.zstack.header.search.APISearchMessage;
import org.zstack.header.search.APISearchReply;
import org.zstack.utils.DebugUtils;
import org.zstack.utils.Utils;
import org.zstack.utils.gson.JSONObjectUtil;
import org.zstack.utils.logging.CLogger;

import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

import static org.zstack.utils.BeanUtils.getProperty;
import static org.zstack.utils.BeanUtils.setProperty;

public class CloudBusImpl3 implements CloudBus, CloudBusIN {
    private static final CLogger logger = Utils.getLogger(CloudBusImpl3.class);

    @Autowired
    private ThreadFacade thdf;
    @Autowired
    private ErrorFacade errf;
    @Autowired
    private ApiTimeoutManager timeoutMgr;
    @Autowired
    private ResourceDestinationMaker destMaker;
    @Autowired
    private PluginRegistry pluginRgty;

    private final String NO_NEED_REPLY_MSG = "noReply";
    private final String CORRELATION_ID = "correlationId";
    private final String REPLY_TO = "replyTo";
    private final String IS_MESSAGE_REPLY = "isReply";
    private long DEFAULT_MESSAGE_TIMEOUT = TimeUnit.MINUTES.toMillis(30);
    private final String TASK_STACK = "task-stack";
    private final String TASK_CONTEXT = "task-context";
    private final String SERVICE_ID_SPLITTER = ":::";

    private String SERVICE_ID = makeLocalServiceId("cloudbus.messages");

    private String EVENT_ID = makeLocalServiceId("cloudbus.events");

    private List<Service> services = new ArrayList<>();
    private Map<Class, List<ReplyMessagePreSendingExtensionPoint>> replyMessageMarshaller = new ConcurrentHashMap<Class, List<ReplyMessagePreSendingExtensionPoint>>();

    private Map<Class, List<BeforeDeliveryMessageInterceptor>> beforeDeliveryMessageInterceptors = new HashMap<Class, List<BeforeDeliveryMessageInterceptor>>();
    private Map<Class, List<BeforeSendMessageInterceptor>> beforeSendMessageInterceptors = new HashMap<Class, List<BeforeSendMessageInterceptor>>();
    private Map<Class, List<BeforePublishEventInterceptor>> beforeEventPublishInterceptors = new HashMap<Class, List<BeforePublishEventInterceptor>>();

    private List<BeforeDeliveryMessageInterceptor> beforeDeliveryMessageInterceptorsForAll = new ArrayList<BeforeDeliveryMessageInterceptor>();
    private List<BeforeSendMessageInterceptor> beforeSendMessageInterceptorsForAll = new ArrayList<BeforeSendMessageInterceptor>();
    private List<BeforePublishEventInterceptor> beforeEventPublishInterceptorsForAll = new ArrayList<BeforePublishEventInterceptor>();
    private Map<String, Map<String, CloudBusEventListener>> eventListeners = new ConcurrentHashMap<>();

    private Set<String> filterMsgNames = new HashSet<>();

    private Map<String, EndPoint> endPoints = new HashMap<>();
    private Map<String, Envelope> envelopes = new ConcurrentHashMap<>();
    private Map<String, java.util.function.Consumer> messageConsumers = new ConcurrentHashMap<>();
    private static TimeoutRestTemplate http = RESTFacade.createRestTemplate(CoreGlobalProperty.REST_FACADE_READ_TIMEOUT, CoreGlobalProperty.REST_FACADE_CONNECT_TIMEOUT);

    public static final String HTTP_BASE_URL = "/cloudbus";

    {
        if (CloudBusGlobalProperty.MESSAGE_LOG != null) {
            String[] msgNames = CloudBusGlobalProperty.MESSAGE_LOG.split(",");
            for (String name : msgNames) {
                filterMsgNames.add(name.trim());
            }
        }

        if (CoreGlobalProperty.UNIT_TEST_ON) {
            CloudBusGlobalProperty.HTTP_CONTEXT_PATH = "";
            CloudBusGlobalProperty.HTTP_PORT = 8989;
        }
    }


    private abstract class Envelope {
        long startTime;

        {
            if (CloudBusGlobalConfig.STATISTICS_ON.value(Boolean.class)) {
                startTime = System.currentTimeMillis();
            }
        }

        void count(Message msg) {
            /*
            if (!CloudBusGlobalConfig.STATISTICS_ON.value(Boolean.class)) {
                return;
            }

            long timeCost = System.currentTimeMillis() - startTime;
            MessageStatistic statistic = statistics.get(msg.getClass().getName());
            statistic.count(timeCost);
            */
        }

        abstract void ack(MessageReply reply);

        abstract void timeout();
    }

    interface ConsumerReceipt {
        void cancel();
    }

    private ConsumerReceipt on(String serviceId, Consumer consumer) {
        Consumer old = messageConsumers.get(serviceId);
        if (old != null && old != consumer) {
            throw new CloudRuntimeException(String.format("duplicate Consumer[%s,%s] for the same service id[%s]", old.getClass(), consumer.getClass(), serviceId));
        }
        messageConsumers.put(serviceId, consumer);

        return () -> messageConsumers.remove(serviceId);
    }

    private Consumer<Event> eventConsumer = new Consumer<Event>() {
        @ExceptionSafe
        private void callListener(Event e, CloudBusEventListener listener) {
            listener.handleEvent(e);
        }

        @Override
        @AsyncThread
        public void accept(Event evt) {
            logger.debug(String.format("[event received]: %s", dumpMessage(evt)));

            Map<String, CloudBusEventListener> ls = eventListeners.get(evt.getType().toString());
            if (ls == null) {
                return;
            }

            ls.values().forEach(l -> callListener(evt, l));
        }
    };

    private Consumer<Message> messageConsumer = new Consumer<Message>() {
        @Override
        @AsyncThread
        public void accept(Message msg) {
            setThreadLoggingContext(msg);

            if (logger.isTraceEnabled() && islogMessage(msg))  {
                logger.trace(String.format("[msg received]: %s", dumpMessage(msg)));
            }

            if (msg instanceof MessageReply) {
                beforeDeliverMessage(msg);

                MessageReply r = (MessageReply) msg;
                String correlationId = r.getHeaderEntry(CORRELATION_ID);
                Envelope e = envelopes.get(correlationId);
                if (e == null) {
                    logger.warn(String.format("received a message reply but no envelope found," +
                            "maybe the message request has been timeout or sender doesn't care about reply." +
                            "drop it. reply dump:\n%s", dumpMessage(r)));
                    return;
                }

                e.ack(r);
            } else {
                dealWithUnknownMessage(msg);
            }
        }
    };

    private String dumpMessage(Message msg) {
        return String.format("%s %s", msg.getClass().getName(), CloudBusGson.toJson(msg));
    }

    private interface EndPoint {
        void active();

        void inactive();
    }

    @Override
    public void activeService(Service serv) {
        activeService(serv.getId());
    }

    @Override
    public void activeService(String id) {
        EndPoint e = endPoints.get(id);
        e.active();
    }

    @Override
    public void deActiveService(Service serv) {
        deActiveService(serv.getId());
    }

    @Override
    public void deActiveService(String id) {
        EndPoint e = endPoints.get(id);
        e.inactive();
    }

    @Override
    public void send(Message msg) {
        send(msg, true);
    }

    @Override
    public <T extends Message> void send(List<T> msgs) {
        msgs.forEach(this::send);
    }

    @Override
    public void send(APIMessage msg, Consumer<APIEvent> consumer) {
        subscribeEvent((e) -> {
            APIEvent ae = (APIEvent) e;
            if (ae.getApiId().equals(msg.getId())) {
                consumer.accept(ae);
                return true;
            }

            return false;
        }, new APIEvent());

        send(msg);
    }

    private void evaluateMessageTimeout(NeedReplyMessage msg) {
        Long timeout = timeoutMgr.getTimeout(msg.getClass());

        if (timeout != null && msg.getTimeout() == -1) {
            msg.setTimeout(timeout);
        }

        if (msg.getTimeout() == -1) {
            msg.setTimeout(DEFAULT_MESSAGE_TIMEOUT);
        }
    }

    private MessageReply createErrorReply(NeedReplyMessage m, ErrorCode err) {
        MessageReply r = new MessageReply();
        r.putHeaderEntry(CORRELATION_ID, m.getId());
        r.setError(err);
        return r;
    }

    private MessageReply createTimeoutReply(NeedReplyMessage m) {
        return createErrorReply(m, errf.stringToTimeoutError(m.toErrorString()));
    }

    @Override
    public void send(NeedReplyMessage msg, CloudBusCallBack callback) {
        evaluateMessageTimeout(msg);

        Envelope e = new Envelope() {
            AtomicBoolean called = new AtomicBoolean(false);

            final Envelope self = this;
            ThreadFacadeImpl.TimeoutTaskReceipt timeoutTaskReceipt = thdf.submitTimeoutTask(self::timeout, TimeUnit.MILLISECONDS, msg.getTimeout());

            @Override
            public void ack(MessageReply reply) {
                count(msg);

                envelopes.remove(msg.getId());

                if (!called.compareAndSet(false, true)) {
                    return;
                }

                timeoutTaskReceipt.cancel();

                callback.run(reply);
            }

            @Override
            public void timeout() {
                envelopes.remove(msg.getId());

                if (!called.compareAndSet(false, true)) {
                    return;
                }

                callback.run(createTimeoutReply(msg));
            }
        };

        envelopes.put(msg.getId(), e);
        send(msg, false);
    }

    @Override
    public void send(List<? extends NeedReplyMessage> msgs, CloudBusListCallBack callBack) {
        send(msgs, msgs.size(), callBack);
    }

    @Override
    public void send(List<? extends NeedReplyMessage> msgs, int parallelLevel, CloudBusListCallBack callBack) {
        DebugUtils.Assert(!msgs.isEmpty(), "you cannot pass an empty message list to me");

        msgs.forEach(this::evaluateMessageTimeout);

        Map<String, MessageReply> replies = Collections.synchronizedMap(new HashMap<>(msgs.size()));

        new While<>(msgs).step((msg, completion) -> send(msg, new CloudBusCallBack(completion) {
            @Override
            public void run(MessageReply reply) {
                replies.put(msg.getId(), reply);
                completion.done();
            }
        }), parallelLevel).run(new NoErrorCompletion(callBack) {
            @Override
            public void done() {
                List<MessageReply> results = new ArrayList<>();
                assert msgs.size() == replies.size();
                msgs.forEach(msg -> results.add(replies.get(msg.getId())));
                callBack.run(results);
            }
        });
    }

    @Override
    public void send(List<? extends NeedReplyMessage> msgs, int parallelLevel, CloudBusSteppingCallback callback) {
        DebugUtils.Assert(!msgs.isEmpty(), "you cannot pass an empty message list to me");

        msgs.forEach(this::evaluateMessageTimeout);
        new While<>(msgs).step((msg, completion) -> send(msg, new CloudBusCallBack(completion) {
            @Override
            public void run(MessageReply reply) {
                callback.run(msg, reply);
                completion.done();
            }
        }), parallelLevel).run(new NopeNoErrorCompletion());
    }

    @Override
    public void route(List<Message> msgs) {
        msgs.forEach(this::route);
    }

    @Override
    public void route(Message msg) {
        if (msg.getServiceId() == null) {
            throw new IllegalArgumentException(String.format("service id cannot be null: %s", msg.getClass().getName()));
        }

        if (msg instanceof NeedReplyMessage) {
            evaluateMessageTimeout((NeedReplyMessage) msg);
        }

        doSendAndCallExtensions(msg);
    }

    private void callReplyPreSendingExtensions(Message msg, NeedReplyMessage msgReq) {
        List<ReplyMessagePreSendingExtensionPoint> exts = replyMessageMarshaller.get(msg.getClass());
        if (exts != null) {
            for (ReplyMessagePreSendingExtensionPoint ext : exts) {
                ext.marshalReplyMessageBeforeSending(msg, msgReq);
            }
        }
    }

    @Override
    public void reply(Message request, MessageReply reply) {
        if (Boolean.valueOf(request.getHeaderEntry(NO_NEED_REPLY_MSG))) {
            if (logger.isTraceEnabled()) {
                logger.trace(String.format("%s in message%s is set, drop reply%s", NO_NEED_REPLY_MSG,
                        dumpMessage(request), dumpMessage(reply)));
            }

            return;
        }

        reply.getHeaders().put(IS_MESSAGE_REPLY, Boolean.TRUE.toString());
        reply.putHeaderEntry(CORRELATION_ID, request.getId());
        reply.setServiceId(request.getHeaderEntry(REPLY_TO));

        if (request instanceof NeedReplyMessage) {
            callReplyPreSendingExtensions(reply, (NeedReplyMessage) request);
        }

        doSend(reply);
    }

    @Override
    public void publish(List<Event> events) {
        events.forEach(this::publish);
    }

    @Override
    public void publish(Event event) {
        if (event instanceof APIEvent) {
            APIEvent aevt = (APIEvent) event;
            DebugUtils.Assert(aevt.getApiId() != null, String.format("apiId of %s cannot be null", aevt.getClass().getName()));
        }

        callReplyPreSendingExtensions(event, null);

        BeforePublishEventInterceptor c = null;
        try {
            List<BeforePublishEventInterceptor> is = beforeEventPublishInterceptors.get(event.getClass());
            if (is != null) {
                for (BeforePublishEventInterceptor i : is) {
                    c = i;
                    i.beforePublishEvent(event);
                }
            }

            for (BeforePublishEventInterceptor i : beforeEventPublishInterceptorsForAll)  {
                c = i;
                i.beforePublishEvent(event);
            }
        } catch (StopRoutingException e) {
            if (logger.isTraceEnabled()) {
                logger.trace(String.format("BeforePublishEventInterceptor[%s] stop publishing event: %s",
                        c == null ? "null" : c.getClass().getName(), dumpMessage(event)));
            }

            return;
        }

        doPublish(event);
    }

    class MessageSender {
        Message msg;
        String managementNodeId;
        String serviceId;
        boolean localSend;

        public MessageSender(Message msg) {
            this.msg = msg;

            serviceId = msg instanceof Event ? EVENT_ID : msg.getServiceId();
            String[] ids = serviceId.split(SERVICE_ID_SPLITTER, 2);
            managementNodeId = ids.length == 1 ? Platform.getManagementServerId() : ids[0];
            localSend = !CloudBusGlobalProperty.HTTP_ALWAYS && managementNodeId.equals(Platform.getManagementServerId());
        }

        void send() {
            if (msg instanceof Event) {
                eventSend();
                return;
            }

            if (localSend) {
                localSend();
            } else {
                httpSend();
            }
        }

        private void httpSend() {
            String ip = destMaker.getNodeInfo(managementNodeId).getNodeIP();
            httpSend(ip);
        }

        private void httpSend(String ip) {
            String url = CloudBusGlobalProperty.HTTP_CONTEXT_PATH.isEmpty() ? String.format("http://%s:%s%s",
                    ip, CloudBusGlobalProperty.HTTP_PORT, HTTP_BASE_URL) : String.format("http://%s:%s/%s/%s",
                    ip, CloudBusGlobalProperty.HTTP_PORT, CloudBusGlobalProperty.HTTP_CONTEXT_PATH, HTTP_BASE_URL);

            msg.putHeaderEntry("schema", new JsonSchemaBuilder(msg).build());

            HttpHeaders headers = new HttpHeaders();
            HttpEntity<String> req = new HttpEntity<>(CloudBusGson.toJson(msg), headers);

            try {
                ResponseEntity<String> rsp = new Retry<ResponseEntity<String>>() {
                    {
                        interval = 2;
                    }

                    @Override
                    @RetryCondition(onExceptions = {IOException.class, RestClientException.class, HttpClientErrorException.class})
                    protected ResponseEntity<String> call() {
                        return http.exchange(url, HttpMethod.POST, req, String.class);
                    }
                }.run();

                if (!rsp.getStatusCode().is2xxSuccessful()) {
                    replyErrorIfNeeded(errf.stringToOperationError(String.format("HTTP ERROR, status code: %s, body: %s", rsp.getStatusCode(), rsp.getBody())));
                }
            } catch (OperationFailureException e) {
                replyErrorIfNeeded(e.getErrorCode());
            } catch (Throwable e) {
                replyErrorIfNeeded(errf.throwableToOperationError(e));
            }
        }

        private void replyErrorIfNeeded(ErrorCode errorCode) {
            if (msg instanceof APIMessage) {
                throw new OperationFailureException(errorCode);
            } else if (msg instanceof NeedReplyMessage) {
                MessageReply reply = createErrorReply((NeedReplyMessage) msg, errorCode);
                messageConsumer.accept(reply);
            }
        }

        private void eventSend() {
            localSend();
            destMaker.getAllNodeInfo().forEach(node -> {
                if (!node.getNodeUuid().equals(Platform.getManagementServerId())) {
                    httpSend(node.getNodeIP());
                }
            });
        }

        private void localSend() {
            Consumer consumer = messageConsumers.get(serviceId);
            if (consumer != null) {
                consumer.accept(msg);
            } else {
                dealWithUnknownMessage(msg);
            }
        }
    }


    private void doPublish(Event evt) {
        evalThreadContextToMessage(evt);

        if (logger.isTraceEnabled() && islogMessage(evt)) {
            logger.trace(String.format("[event publish]: %s", dumpMessage(evt)));
        }

        new MessageSender(evt).send();
    }

    @Override
    public MessageReply call(NeedReplyMessage msg) {
        FutureReturnValueCompletion future = new FutureReturnValueCompletion(null);
        send(msg, new CloudBusCallBack(future) {
            @Override
            public void run(MessageReply reply) {
                future.success(reply);
            }
        });

        future.await();
        return future.getResult();
    }

    @Override
    public <T extends NeedReplyMessage> List<MessageReply> call(List<T> msgs) {
        FutureReturnValueCompletion future = new FutureReturnValueCompletion(null);

        DebugUtils.Assert(!msgs.isEmpty(), "cannot call empty messages");
        send(msgs, new CloudBusListCallBack(future) {
            @Override
            public void run(List<MessageReply> replies) {
                future.success(replies);
            }
        });

        future.await();
        return future.getResult();
    }

    private void setThreadLoggingContext(Message msg) {
        ThreadContext.clearAll();

        if (msg instanceof APIMessage) {
            ThreadContext.put(Constants.THREAD_CONTEXT_API, msg.getId());
            ThreadContext.put(Constants.THREAD_CONTEXT_TASK_NAME, msg.getClass().getName());
        } else {
            Map<String, String> ctx = msg.getHeaderEntry(TASK_CONTEXT);
            if (ctx != null) {
                ThreadContext.putAll(ctx);
            }
        }

        if (msg.getHeaders().containsKey(TASK_STACK)) {
            List<String> taskStack = msg.getHeaderEntry(TASK_STACK);
            ThreadContext.setStack(taskStack);
        }
    }

    private boolean islogMessage(Message msg) {
        if (CloudBusGlobalProperty.READ_API_LOG_OFF && (msg instanceof APISyncCallMessage || msg instanceof APIReply)) {
            return false;
        } else if (CloudBusGlobalProperty.MESSAGE_LOG_FILTER_ALL) {
            return !filterMsgNames.contains(msg.getClass().getName());
        } else {
            return filterMsgNames.contains(msg.getClass().getName());
        }
    }

    private void beforeDeliverMessage(Message msg) {
        List<BeforeDeliveryMessageInterceptor> is = beforeDeliveryMessageInterceptors.get(msg.getClass());
        if (is != null) {
            for (BeforeDeliveryMessageInterceptor i : is) {
                i.beforeDeliveryMessage(msg);
            }
        }

        for (BeforeDeliveryMessageInterceptor i : beforeDeliveryMessageInterceptorsForAll) {
            i.beforeDeliveryMessage(msg);
        }
    }

    @Override
    public void registerService(Service serv) throws CloudConfigureFailException {
        int syncLevel = serv.getSyncLevel();

        EndPoint endPoint = new EndPoint() {
            ConsumerReceipt registration;
            Consumer<Message> consumer = msg -> {
                try {
                    if (logger.isTraceEnabled() && islogMessage(msg)) {
                        logger.trace(String.format("[msg received]: %s", dumpMessage(msg)));
                    }

                    SyncTask<Void> task = new SyncTask<Void>() {
                        @Override
                        public String getSyncSignature() {
                            return serv.getId();
                        }

                        @Override
                        public int getSyncLevel() {
                            return syncLevel;
                        }

                        @Override
                        public String getName() {
                            return String.format("CloudBus EndPoint[%s]", serv.getId());
                        }

                        @Override
                        public Void call() {
                            setThreadLoggingContext(msg);

                            try {
                                beforeDeliverMessage(msg);

                                serv.handleMessage(msg);
                            } catch (Throwable t) {
                                logExceptionWithMessageDump(msg, t);

                                if (t instanceof OperationFailureException) {
                                    replyErrorByMessageType(msg, ((OperationFailureException) t).getErrorCode());
                                } else {
                                    replyErrorByMessageType(msg, errf.stringToInternalError(t.getMessage()));
                                }
                            }

                            return null;
                        }
                    };

                    if (syncLevel == 0) {
                        thdf.submit(task);
                    } else {
                        thdf.syncSubmit(task);
                    }
                } catch (Throwable t) {
                    logger.warn("unhandled throwable", t);
                }
            };

            @Override
            public void active() {
                registration = on(serv.getId(), consumer);
            }

            @Override
            public void inactive() {
                if (registration != null) {
                    registration.cancel();
                }
            }
        };

        DebugUtils.Assert(!endPoints.containsKey(serv.getId()), String.format("duplicate services[id:%s]", serv.getId()));
        endPoints.put(serv.getId(), endPoint);
        endPoint.active();

        logger.debug(String.format("registered service[%s]", serv.getId()));
    }

    @Override
    public void unregisterService(Service serv) {
        EndPoint ep = endPoints.get(serv.getId());
        if (ep != null) {
            ep.inactive();
        }
        endPoints.remove(serv.getId());
    }

    @Override
    public EventSubscriberReceipt subscribeEvent(CloudBusEventListener listener, Event... events) {
        String key = Platform.getUuid();

        for (Event event : events) {
            Map m = eventListeners.computeIfAbsent(event.getType().toString(), k->new ConcurrentHashMap<>());
            m.put(key, listener);
        }

        return new EventSubscriberReceipt() {
            @Override
            public void unsubscribe(Event e) {
                Map m = eventListeners.get(e.getType().toString());
                m.remove(key);
            }

            @Override
            public void unsubscribeAll() {
                for (Event event : events) {
                    unsubscribe(event);
                }
            }
        };
    }

    @Override
    public void dealWithUnknownMessage(Message msg) {
        String details = String.format("No service deals with message: %s", dumpMessage(msg));
        if (msg instanceof APISyncCallMessage) {
            APIReply reply = new APIReply();
            reply.setError(errf.instantiateErrorCode(SysErrors.UNKNOWN_MESSAGE_ERROR, details));
            reply.setSuccess(false);
            reply(msg, reply);
        } else if (msg instanceof APIMessage) {
            APIEvent evt = new APIEvent(msg.getId());
            evt.setError(errf.instantiateErrorCode(SysErrors.UNKNOWN_MESSAGE_ERROR, details));
            publish(evt);
        } else if (msg instanceof NeedReplyMessage) {
            MessageReply reply = new MessageReply();
            reply.setError(errf.instantiateErrorCode(SysErrors.UNKNOWN_MESSAGE_ERROR, details));
            reply.setSuccess(false);
            reply(msg, reply);
        }

        DebugUtils.dumpStackTrace("Dropped an unknown message, " + details);
    }

    private void replyErrorIfMessageNeedReply(Message msg, ErrorCode code) {
        if (msg instanceof NeedReplyMessage) {
            MessageReply reply = new MessageReply();
            reply.setError(code);
            reply.setSuccess(false);
            reply(msg, reply);
        }
    }

    @Override
    public void replyErrorByMessageType(Message msg, Exception e) {
        if (e instanceof OperationFailureException) {
            replyErrorByMessageType(msg, ((OperationFailureException) e).getErrorCode());
        } else {
            replyErrorByMessageType(msg, e.getMessage());
        }
    }

    private void replyErrorIfMessageNeedReply(Message msg, String errStr) {
        if (msg instanceof NeedReplyMessage) {
            ErrorCode err = errf.stringToInternalError(errStr);
            replyErrorIfMessageNeedReply(msg, err);
        } else {
            DebugUtils.dumpStackTrace(String.format("An error happened when dealing with message[%s], because this message doesn't need a reply, we call it out loudly\nerror: %s\nmessage dump: %s", msg.getClass().getName(), errStr, dumpMessage(msg)));
        }
    }

    private void replyErrorToApiMessage(APIMessage msg, ErrorCode err) {
        if (msg instanceof APISyncCallMessage) {
            APIReply reply = new APIReply();
            reply.setError(err);
            reply.setSuccess(false);
            this.reply(msg, reply);
        } else if (msg instanceof APISearchMessage) {
            APISearchReply reply = new APISearchReply();
            reply.setError(err);
            reply.setSuccess(false);
            this.reply(msg, reply);
        } else {
            APIEvent evt = new APIEvent(msg.getId());
            evt.setError(err);
            evt.setSuccess(false);
            this.publish(evt);
        }
    }

    private void replyErrorToApiMessage(APIMessage msg, String err) {
        replyErrorToApiMessage(msg, errf.stringToInternalError(err));
    }

    @Override
    public void replyErrorByMessageType(Message msg, String err) {
        if (msg instanceof APIMessage) {
            replyErrorToApiMessage((APIMessage) msg, err);
        } else {
            replyErrorIfMessageNeedReply(msg, err);
        }
    }

    @Override
    public void replyErrorByMessageType(Message msg, ErrorCode err) {
        if (msg instanceof APIMessage) {
            replyErrorToApiMessage((APIMessage) msg, err);
        } else {
            replyErrorIfMessageNeedReply(msg, err);
        }
    }

    @Override
    public void logExceptionWithMessageDump(Message msg, Throwable e) {
        if (!(e instanceof OperationFailureException)) {
            logger.warn(String.format("unhandled throwable happened when dealing with message[%s], dump: %s", msg.getClass().getName(), dumpMessage(msg)), e);
        }
    }

    private String toServiceId(String serviceId, String mgmtId) {
        return mgmtId + SERVICE_ID_SPLITTER + serviceId;
    }

    @Override
    public String makeLocalServiceId(String serviceId) {
        return toServiceId(serviceId, Platform.getManagementServerId());
    }

    @Override
    public void makeLocalServiceId(Message msg, String serviceId) {
        msg.setServiceId(makeLocalServiceId(serviceId));
    }

    @Override
    public String makeServiceIdByManagementNodeId(String serviceId, String managementNodeId) {
        return toServiceId(serviceId, managementNodeId);
    }

    @Override
    public void makeServiceIdByManagementNodeId(Message msg, String serviceId, String managementNodeId) {
        msg.setServiceId(makeServiceIdByManagementNodeId(serviceId, managementNodeId));
    }

    @Override
    public String makeTargetServiceIdByResourceUuid(String serviceId, String resourceUuid) {
        DebugUtils.Assert(serviceId!=null, "serviceId cannot be null");
        DebugUtils.Assert(resourceUuid!=null, "resourceUuid cannot be null");
        String mgmtUuid = destMaker.makeDestination(resourceUuid);
        return toServiceId(serviceId, mgmtUuid);
    }

    @Override
    public void makeTargetServiceIdByResourceUuid(Message msg, String serviceId, String resourceUuid) {
        String targetService = makeTargetServiceIdByResourceUuid(serviceId, resourceUuid);
        msg.setServiceId(targetService);
    }

    @Override
    public void installBeforeDeliveryMessageInterceptor(BeforeDeliveryMessageInterceptor interceptor, Class<? extends Message>... classes) {
        if (classes.length == 0) {
            int order = 0;
            for (BeforeDeliveryMessageInterceptor i : beforeDeliveryMessageInterceptorsForAll) {
                if (i.orderOfBeforeDeliveryMessageInterceptor() <= interceptor.orderOfBeforeDeliveryMessageInterceptor()) {
                    order = beforeDeliveryMessageInterceptorsForAll.indexOf(i);
                    break;
                }
            }

            beforeDeliveryMessageInterceptorsForAll.add(order, interceptor);
            return;
        }

        for (Class clz : classes) {
            while (clz != Object.class) {
                List<BeforeDeliveryMessageInterceptor> is = beforeDeliveryMessageInterceptors.get(clz);
                if (is == null) {
                    is = new ArrayList<>();
                    beforeDeliveryMessageInterceptors.put(clz, is);
                }

                synchronized (is) {
                    int order = 0;
                    for (BeforeDeliveryMessageInterceptor i : is) {
                        if (i.orderOfBeforeDeliveryMessageInterceptor() <= interceptor.orderOfBeforeDeliveryMessageInterceptor()) {
                            order = is.indexOf(i);
                            break;
                        }
                    }
                    is.add(order, interceptor);
                }

                clz = clz.getSuperclass();
            }
        }
    }

    @Override
    public void installBeforeSendMessageInterceptor(BeforeSendMessageInterceptor interceptor, Class<? extends Message>... classes) {
        if (classes.length == 0) {
            int order = 0;
            for (BeforeSendMessageInterceptor i : beforeSendMessageInterceptorsForAll) {
                if (i.orderOfBeforeSendMessageInterceptor() <= interceptor.orderOfBeforeSendMessageInterceptor()) {
                    order = beforeSendMessageInterceptorsForAll.indexOf(i);
                    break;
                }
            }

            beforeSendMessageInterceptorsForAll.add(order, interceptor);
            return;
        }

        for (Class clz : classes) {
            while (clz != Object.class) {
                List<BeforeSendMessageInterceptor> is = beforeSendMessageInterceptors.get(clz);
                if (is == null) {
                    is = new ArrayList<>();
                    beforeSendMessageInterceptors.put(clz, is);
                }

                synchronized (is) {
                    int order = 0;
                    for (BeforeSendMessageInterceptor i : is) {
                        if (i.orderOfBeforeSendMessageInterceptor() <= interceptor.orderOfBeforeSendMessageInterceptor()) {
                            order = is.indexOf(i);
                            break;
                        }
                    }
                    is.add(order, interceptor);
                }

                clz = clz.getSuperclass();
            }
        }
    }

    @Override
    public void installBeforePublishEventInterceptor(BeforePublishEventInterceptor interceptor, Class<? extends Event>... classes) {
        if (classes.length == 0) {
            int order = 0;
            for (BeforePublishEventInterceptor i : beforeEventPublishInterceptorsForAll) {
                if (i.orderOfBeforePublishEventInterceptor() <= interceptor.orderOfBeforePublishEventInterceptor()) {
                    order = beforeEventPublishInterceptorsForAll.indexOf(i);
                    break;
                }
            }

            beforeEventPublishInterceptorsForAll.add(order, interceptor);
            return;
        }

        for (Class clz : classes) {
            while (clz != Object.class) {
                List<BeforePublishEventInterceptor> is = beforeEventPublishInterceptors.get(clz);
                if (is == null) {
                    is = new ArrayList<>();
                    beforeEventPublishInterceptors.put(clz, is);
                }

                synchronized (is) {
                    int order = 0;
                    for (BeforePublishEventInterceptor i : is) {
                        if (i.orderOfBeforePublishEventInterceptor() <= interceptor.orderOfBeforePublishEventInterceptor()) {
                            order = is.indexOf(i);
                            break;
                        }
                    }
                    is.add(order, interceptor);
                }

                clz = clz.getSuperclass();
            }
        }
    }

    private void populateExtension() {
        services = pluginRgty.getExtensionList(Service.class);
        services.forEach(serv->{
            assert serv.getId() != null : String.format("service id can not be null[%s]", serv.getClass().getName());
            registerService(serv);
        });

        for (ReplyMessagePreSendingExtensionPoint extp : pluginRgty.getExtensionList(ReplyMessagePreSendingExtensionPoint.class)) {
            List<Class> clazzs = extp.getReplyMessageClassForPreSendingExtensionPoint();
            if (clazzs == null || clazzs.isEmpty()) {
                continue;
            }

            for (Class clz : clazzs) {
                if (!(APIEvent.class.isAssignableFrom(clz)) && !(MessageReply.class.isAssignableFrom(clz))) {
                    throw new CloudRuntimeException(String.format("ReplyMessagePreSendingExtensionPoint can only marshal APIEvent or MessageReply. %s claimed by %s is neither APIEvent nor MessageReply",
                            clz.getName(), extp.getClass().getName()));
                }

                List<ReplyMessagePreSendingExtensionPoint> exts = replyMessageMarshaller.get(clz);
                if (exts == null) {
                    exts = new ArrayList<>();
                    replyMessageMarshaller.put(clz, exts);
                }
                exts.add(extp);
            }
        }
    }

    @Override
    public boolean start() {
        on(SERVICE_ID, messageConsumer);
        on(EVENT_ID, eventConsumer);

        populateExtension();
        return true;
    }

    @Override
    public boolean stop() {
        return true;
    }

    private void evalThreadContextToMessage(Message msg) {
        Map<String, String> ctx = ThreadContext.getImmutableContext();
        if (ctx != null) {
            msg.putHeaderEntry(TASK_CONTEXT, new HashMap<>(ctx));
        }

        List<String> list = ThreadContext.getImmutableStack().asList();
        if (list != null && !list.isEmpty()) {
            msg.putHeaderEntry(TASK_STACK, new ArrayList<>(list));
        }
    }

    private void doSendAndCallExtensions(Message msg) {
        // for unit test finding invocation chain
        MessageCommandRecorder.record(msg.getClass());

        List<BeforeSendMessageInterceptor> interceptors = beforeSendMessageInterceptors.get(msg.getClass());
        if (interceptors != null) {
            for (BeforeSendMessageInterceptor interceptor : interceptors) {
                interceptor.beforeSendMessage(msg);
            }
        }

        for (BeforeSendMessageInterceptor interceptor : beforeSendMessageInterceptorsForAll) {
            interceptor.beforeSendMessage(msg);
        }

        doSend(msg);
    }

    private void doSend(Message msg) {
        evalThreadContextToMessage(msg);

        if (logger.isTraceEnabled() && islogMessage(msg)) {
            logger.trace(String.format("[msg send]: %s", dumpMessage(msg)));
        }

        new MessageSender(msg).send();
    }

    private void send(Message msg, Boolean noNeedReply) {
        if (msg.getServiceId() == null) {
            throw new IllegalArgumentException(String.format("service id cannot be null: %s", msg.getClass().getName()));
        }

        msg.putHeaderEntry(CORRELATION_ID, msg.getId());
        msg.putHeaderEntry(REPLY_TO, SERVICE_ID);
        if (msg instanceof APIMessage) {
            // API always need reply
            msg.putHeaderEntry(NO_NEED_REPLY_MSG, Boolean.FALSE.toString());
        } else if (msg instanceof NeedReplyMessage) {
            // for NeedReplyMessage sent without requiring receiver to reply,
            // mark it, then it will not be tracked and replied
            msg.putHeaderEntry(NO_NEED_REPLY_MSG, noNeedReply.toString());
        }

        doSendAndCallExtensions(msg);
    }

    private void restoreFromSchema(Message msg, Map raw) throws ClassNotFoundException {
        Map<String, String> schema = msg.getHeaderEntry("schema");
        if (schema == null || schema.isEmpty()) {
            return;
        }

        raw = (Map) raw.values().iterator().next();
        List<String> paths = new ArrayList<>();
        paths.addAll(schema.keySet());

        for (String p : paths) {
            Object dst = getProperty(msg, p);
            String type = schema.get(p);

            if (dst.getClass().getName().equals(type)) {
                continue;
            }

            Class clz = Class.forName(type);
            setProperty(msg, p, JSONObjectUtil.rehashObject(getProperty(raw, p), clz));
        }
    }

    @AsyncThread
    void handleHttpRequest(HttpEntity<String> e, HttpServletResponse rsp) {
        Message msg = CloudBusGson.fromJson(e.getBody());
        Map raw = JSONObjectUtil.toObject(e.getBody(), LinkedHashMap.class);
        try {
            restoreFromSchema(msg, raw);
        } catch (ClassNotFoundException e1) {
            throw new CloudRuntimeException(e1);
        }

        new MessageSender(msg).localSend();
        rsp.setStatus(HttpStatus.OK.value());
    }
}