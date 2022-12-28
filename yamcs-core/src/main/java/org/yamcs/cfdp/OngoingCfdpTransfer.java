package org.yamcs.cfdp;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import org.yamcs.YConfiguration;
import org.yamcs.YamcsServer;
import org.yamcs.cfdp.pdu.*;
import org.yamcs.events.EventProducer;
import org.yamcs.filetransfer.TransferMonitor;
import org.yamcs.logging.Log;
import org.yamcs.protobuf.TransferState;
import org.yamcs.utils.StringConverter;
import org.yamcs.yarch.Stream;

public abstract class OngoingCfdpTransfer implements CfdpFileTransfer {
    protected final CfdpTransactionId cfdpTransactionId;
    private Stream cfdpOut;
    protected TransferState state;
    protected final ScheduledThreadPoolExecutor executor;
    protected final EventProducer eventProducer;
    protected boolean acknowledged = false;
    protected final Log log;
    protected final long startTime;
    protected final long wallclockStartTime;
    protected final long creationTime;

    protected String transferType = PredefinedTransferTypes.UNKNOWN.toString();

    final TransferMonitor monitor;
    final long destinationId;

    // transaction unique identifier (coming from a database)
    final long id;

    protected ScheduledFuture<?> inactivityFuture;

    final long inactivityTimeout;

    long maxAckSendFreqNanos;
    long lastAckSentTime;
    boolean logAckDrop = true;

    // accumulate the errors
    List<String> errors = new ArrayList<>();

    enum FaultHandlingAction {
        // "Handler code" in protocol?
        //‘0000’ — reserved for future expansion
        //‘0001’ — issue Notice of Cancellation
        //‘0010’ — issue Notice of Suspension
        //‘0011’ — Ignore error
        //‘0100’ — Abandon transaction
        //‘0101’–‘1111’ — reserved
        SUSPEND, CANCEL, ABANDON;

        public static FaultHandlingAction fromString(String str) {
            for (FaultHandlingAction a : values()) {
                if (a.name().equalsIgnoreCase(str)) {
                    return a;
                }
            }
            return null;
        }

        public static List<String> actions() {
            return Arrays.stream(FaultHandlingAction.values()).map(a -> a.name().toLowerCase())
                    .collect(Collectors.toList());
        }
    }

    final Map<ConditionCode, FaultHandlingAction> faultHandlerActions;

    public OngoingCfdpTransfer(String yamcsInstance, long id, long creationTime, ScheduledThreadPoolExecutor executor,
            YConfiguration config, CfdpTransactionId cfdpTransactionId, long destinationId, Stream cfdpOut,
            EventProducer eventProducer, TransferMonitor monitor,
            Map<ConditionCode, FaultHandlingAction> faultHandlerActions) {
        this.cfdpTransactionId = cfdpTransactionId;
        this.cfdpOut = cfdpOut;
        this.state = TransferState.RUNNING;
        this.executor = executor;
        this.eventProducer = eventProducer;
        this.startTime = YamcsServer.getTimeService(yamcsInstance).getMissionTime();
        this.wallclockStartTime = System.currentTimeMillis();
        this.log = new Log(this.getClass(), yamcsInstance);
        this.id = id;
        this.destinationId = destinationId;
        this.creationTime = creationTime;
        if (monitor == null) {
            throw new NullPointerException("the monitor cannot be null");
        }
        this.monitor = monitor;
        this.inactivityTimeout = config.getLong("inactivityTimeout", 10000);

        this.maxAckSendFreqNanos = config.getLong("maxAckSendFreq", 500) * 1_000_000;
        this.faultHandlerActions = faultHandlerActions;
    }

    /**
     * Sets the transfer type fom the metadata packet
     * @param metadata Metadata packet
     */
    protected static String getTransferType(MetadataPacket metadata) {
        if (metadata == null) return PredefinedTransferTypes.UNKNOWN.toString();

        String transferType;
        if(metadata.getFileLength() == 0) {
            if(!metadata.getHeader().isLargeFile()) {
                if(metadata.getOptions() != null && !metadata.getOptions().isEmpty()) {// TODO: maybe put it outside of file checks?
                    transferType = PredefinedTransferTypes.UNSUPPORTED_METADATA_OPTIONS.toString();
                    ArrayList<String> options = new ArrayList<>();
                    for (TLV option : metadata.getOptions()) {
                        if (option.getType() == TLV.TYPE_MESSAGE_TO_USER) {
                            transferType = PredefinedTransferTypes.UNSUPPORTED_METADATA_MESSAGE.toString();

                            byte[] value = option.getValue();
                            if (value.length >= 5 && new String(Arrays.copyOfRange(value, 0, 4)).equals("cfdp")) { // Reserved CFDP message: 32bits = "cfdp" + 8bits message type

                                ReservedMessageToUser reservedMessage = new ReservedMessageToUser(
                                        ByteBuffer.wrap(value));
                                switch (reservedMessage.getMessageType()) {
                                case ORIGINATING_TRANSACTION_ID:
                                    // Ignoring originating transaction ID if there are other types
                                    transferType = PredefinedTransferTypes.ORIGINATING_TRANSACTION_ID_ONLY.toString();
                                    break;
                                case PROXY_PUT_REQUEST:
                                    ProxyPutRequest proxyPutRequest = new ProxyPutRequest(reservedMessage.getContent());
                                    options.add(PredefinedTransferTypes.DOWNLOAD_REQUEST + " (" + proxyPutRequest.getDestinationFileName() + " ⟵ " + proxyPutRequest.getSourceFileName() + ")");
                                    break;
                                case PROXY_PUT_RESPONSE:
                                    ProxyPutResponse proxyPutResponse = new ProxyPutResponse(reservedMessage.getContent());
                                    options.add(PredefinedTransferTypes.DOWNLOAD_REQUEST_RESPONSE.toString() + " (" + proxyPutResponse.getConditionCode() + ", " + (proxyPutResponse.isDataComplete() ? "Complete" : "Incomplete") + ", " + proxyPutResponse.getFileStatus() + ")");
                                    break;
                                case DIRECTORY_LISTING_REQUEST:
                                    options.add(PredefinedTransferTypes.DIRECTORY_LISTING_REQUEST.toString());
                                    break;
                                case DIRECTORY_LISTING_RESPONSE:
                                    options.add(PredefinedTransferTypes.DIRECTORY_LISTING_RESPONSE.toString());
                                    break;
                                default:
                                    options.add(reservedMessage.getMessageType().toString());
                                }
                            }
                        }
                    }
                    if(!options.isEmpty()) {
                        transferType = String.join(", ", options);
                    }
                }
                else {
                    transferType = PredefinedTransferTypes.UNKNOWN_EMPTY_FILE.toString();
                }
            } else {
                transferType = PredefinedTransferTypes.LARGE_FILE_TRANSFER.toString();
            }
        } else {
            transferType = PredefinedTransferTypes.FILE_TRANSFER.toString();
        }
        return transferType;
    }

    public abstract void processPacket(CfdpPacket packet);

    protected void pushError(String err) {
        errors.add(err);
    }
    protected void sendPacket(CfdpPacket packet) {
        if (log.isDebugEnabled()) {
            log.debug("TXID{} sending PDU: {}", cfdpTransactionId, packet);
            log.trace("{}", StringConverter.arrayToHexString(packet.toByteArray(), true));
        }
        cfdpOut.emitTuple(packet.toTuple(this));
    }

    public final boolean isOngoing() {
        return state == TransferState.RUNNING || state == TransferState.PAUSED;
    }

    public final TransferState getTransferState() {
        return state;
    }

    @Override
    public boolean cancellable() {
        return true;
    }

    @Override
    public boolean pausable() {
        return true;
    }

    protected abstract void onInactivityTimerExpiration();

    protected void cancelInactivityTimer() {
        if (inactivityFuture != null) {
            inactivityFuture.cancel(false);
        }
    }

    protected void rescheduleInactivityTimer() {
        cancelInactivityTimer();
        inactivityFuture = executor.schedule(this::onInactivityTimerExpiration, inactivityTimeout,
                TimeUnit.MILLISECONDS);
    }

    public OngoingCfdpTransfer pauseTransfer() {
        executor.submit(this::suspend);
        return this;
    }

    protected abstract void suspend();

    public OngoingCfdpTransfer resumeTransfer() {
        executor.submit(this::resume);
        return this;
    }

    protected abstract void resume();

    public OngoingCfdpTransfer cancelTransfer() {
        executor.submit(() -> {
            pushError("Cancel request received");
            cancel(ConditionCode.CANCEL_REQUEST_RECEIVED);
        });
        return this;
    }

    protected abstract void cancel(ConditionCode code);

    public OngoingCfdpTransfer abandonTransfer(String reason) {
        executor.submit(() -> failTransfer(reason));
        return this;
    }

    // @Override
    public CfdpTransactionId getTransactionId() {
        return cfdpTransactionId;
    }

    @Override
    public boolean isReliable() {
        return acknowledged;
    }

    @Override
    public long getStartTime() {
        return startTime;
    }

    protected void failTransfer(String failureReason) {
        pushError(failureReason);
        changeState(TransferState.FAILED);
    }

    protected void changeState(TransferState newState) {
        this.state = newState;
        if (state != TransferState.RUNNING) {
            cancelInactivityTimer();
        }
        monitor.stateChanged(this);
    }

    @Override
    public String getFailuredReason() {
        return errors.stream().collect(Collectors.joining("; "));
    }

    @Override
    public long getId() {
        return id;
    }

    protected FaultHandlingAction getFaultHandlingAction(ConditionCode code) {
        FaultHandlingAction action = faultHandlerActions.get(code);
        if (action == null) {
            return FaultHandlingAction.CANCEL;
        } else {
            return action;
        }
    }

    /**
     * Return the entity id of the Sender
     *
     * @return
     */
    public long getInitiatorId() {
        return cfdpTransactionId.getInitiatorEntity();
    }

    /**
     * Return the entity id of the Receiver
     *
     * @return
     */
    public long getDestinationId() {
        return destinationId;
    }

    @Override
    public String getTransferType() {
        return transferType;
    }
    
    protected void sendInfoEvent(String type, String msg) {
        eventProducer.sendInfo(type, "TXID[" + cfdpTransactionId + "] " + msg);
    }

    protected void sendWarnEvent(String type, String msg) {
        eventProducer.sendWarning(type, "TXID[" + cfdpTransactionId + "] " + msg);
    }

    protected String toEventMsg(MetadataPacket packet) {
        return packet.toJson();
    }

    public long getCreationTime() {
        return creationTime;
    }
}
