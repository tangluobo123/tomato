package com.tangluobo.tomato.rdp;

import java.io.EOFException;
import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Level;
import java.util.logging.Logger;

import com.sshtools.javardp.IContext;
import com.sshtools.javardp.OrderException;
import com.sshtools.javardp.RdesktopDisconnectException;
import com.sshtools.javardp.RdesktopException;
import com.sshtools.javardp.SecurityType;
import com.sshtools.javardp.State;
import com.sshtools.javardp.layers.Rdp;
import com.sshtools.javardp.rdp5.VChannels;

/**
 * 修复版RDP层，覆盖关键方法添加修复和诊断日志。
 */
public class RdpPatch extends Rdp {

    private static final Logger logger = Logger.getLogger(RdpPatch.class.getName());

    private final State stateRef;
    private final AtomicInteger bitmapUpdateCount = new AtomicInteger(0);
    private final AtomicInteger rdp5PacketCount = new AtomicInteger(0);
    private final AtomicInteger totalPduCount = new AtomicInteger(0);
    private volatile long lastReceiveEnterTime = 0;
    private volatile int lastReasonSeen = 0;
    private volatile int lastServerStatusSeen = 0;
    private volatile boolean refreshSent = false;
    private final java.util.List<String> pduHistory = java.util.Collections.synchronizedList(new java.util.ArrayList<>());

    // 反射访问Rdp的private方法/字段
    private final Method receiveMethod;
    private final Method processPacketMethod;
    private final Method initDataMethod;
    private final Method sendDataMethod;
    private final Field streamField;
    private final Field nextPacketField;

    public RdpPatch(IContext context, State state, VChannels channels) {
        super(context, state, channels);
        this.stateRef = state;

        // 反射获取private方法和字段
        Method rm = null, pm = null, im = null, sm = null;
        Field sf = null, npf = null;
        try {
            rm = Rdp.class.getDeclaredMethod("receive", int[].class);
            rm.setAccessible(true);
            pm = Rdp.class.getDeclaredMethod("processPacket", int[].class, com.sshtools.javardp.Packet.class);
            pm.setAccessible(true);
            im = Rdp.class.getDeclaredMethod("initData", int.class);
            im.setAccessible(true);
            sm = Rdp.class.getDeclaredMethod("sendData", com.sshtools.javardp.Packet.class, int.class);
            sm.setAccessible(true);
            sf = Rdp.class.getDeclaredField("stream");
            sf.setAccessible(true);
            npf = Rdp.class.getDeclaredField("next_packet");
            npf.setAccessible(true);
        } catch (Exception e) {
            logger.log(Level.SEVERE, "反射获取Rdp私有方法/字段失败: " + e.getMessage(), e);
        }
        receiveMethod = rm;
        processPacketMethod = pm;
        initDataMethod = im;
        sendDataMethod = sm;
        streamField = sf;
        nextPacketField = npf;
    }

    @Override
    public void rdp5_process(com.sshtools.javardp.Packet s, boolean encryption, boolean shortform)
            throws RdesktopException, OrderException {
        int pktNum = rdp5PacketCount.incrementAndGet();
        boolean isSSL = stateRef.getSecurityType() == SecurityType.SSL
                || stateRef.getSecurityType() == SecurityType.HYBRID;

        logger.info(String.format("[RDP5 #%d] encryption=%b, shortform=%b, securityType=%s, dataSize=%d",
                pktNum, encryption, shortform, stateRef.getSecurityType(), s.getEnd() - s.getPosition()));

        if (encryption && isSSL) {
            logger.info("[RDP5] Ignoring encryption flag for SSL/HYBRID");
            encryption = false;
        }

        int length, count;
        int type;
        int next;

        if (encryption) {
            s.incrementPosition(shortform ? 6 : 7);
            byte[] data = new byte[s.size() - s.getPosition()];
            s.copyToByteArray(data, 0, s.getPosition(), data.length);
            byte[] packet = secureLayer.decrypt(data);
            if (packet != null) {
                s.copyFromByteArray(packet, 0, s.getPosition(), packet.length);
            }
        }

        while (s.getPosition() < s.getEnd()) {
            // 修复：正确解析 updateHeader 位域 (MS-RDPBCGR 2.2.9.1.2.1)
            // updateHeader (1 byte):
            //   updateCode (4 bits, bits 7-4 高4位) - 更新类型
            //   fragmentation (2 bits, bits 3-2) - 分片标志
            //   compression (2 bits, bits 1-0 低2位) - 压缩标志
            //
            // 原始 javardp 库直接 switch(整个字节)，导致 updateCode=1 (bitmap) 时
            // 字节值=0x10 不匹配 case 1，fast-path bitmap 全部丢失 → 黑屏
            int updateHeader = s.get8();
            int updateCode = (updateHeader >> 4) & 0x0F;
            int fragmentation = (updateHeader >> 2) & 0x03;
            int compression = updateHeader & 0x03;

            // compression != 0 时，updateHeader 后存在 1 字节 compressionFlags
            // 原始代码未处理此字段，会导致 length/next 解析错位
            int compressionFlags = 0;
            if (compression != 0) {
                compressionFlags = s.get8();
            }

            length = s.getLittleEndian16();
            next = s.getPosition() + length;
            type = updateCode; // 用 updateCode 作为 switch 类型

            logger.info(String.format("[RDP5 #%d] updateHeader=0x%02x, updateCode=%d, frag=%d, comp=%d(compFlags=0x%02x), length=%d",
                    pktNum, updateHeader, updateCode, fragmentation, compression, compressionFlags, length));

            // 诊断：分片 bitmap update 警告（单片处理可能解析失败）
            if (type == 1 && fragmentation != 0) {
                logger.warning(String.format("[RDP5 #%d] bitmap update 分片未合并: frag=%d (0=SINGLE,1=LAST,2=FIRST,3=NEXT)", pktNum, fragmentation));
            }

            switch (type) {
            case 0: // orders
                count = s.getLittleEndian16();
                orders.processOrders(s, next, count);
                break;
            case 1: // bitmap update
                s.incrementPosition(2);
                processBitmapUpdates(s);
                break;
            case 2: // palette
                s.incrementPosition(2);
                processPalette(s);
                break;
            case 3: break;
            case 5: process_null_system_pointer_pdu(s); break;
            case 6: break;
            case 9: process_colour_pointer_pdu(s); break;
            case 10: process_cached_pointer_pdu(s); break;
            default:
                logger.warning("Unimplemented RDP5 updateCode " + type
                        + " (updateHeader=0x" + String.format("%02x", updateHeader) + ")");
            }
            s.setPosition(next);
        }
    }

    @Override
    public void rdp5_process(com.sshtools.javardp.Packet s, boolean e)
            throws RdesktopException, OrderException {
        rdp5_process(s, e, false);
    }

    @Override
    protected void processBitmapUpdates(com.sshtools.javardp.Packet data) throws RdesktopException {
        int count = bitmapUpdateCount.incrementAndGet();
        int pos = data.getPosition();
        int n_updates = data.getLittleEndian16();
        logger.info(String.format("[BITMAP UPDATE #%d] n_updates=%d", count, n_updates));
        data.setPosition(pos);
        super.processBitmapUpdates(data);

        try {
            java.awt.image.BufferedImage bi = stateRef.getCanvas().getDisplay().getBufferedImage();
            if (bi != null) {
                int w = bi.getWidth(), h = bi.getHeight();
                int[] xs = {0, w / 2, w - 1};
                int[] ys = {0, h / 2, h - 1};
                StringBuilder sb = new StringBuilder("[BITMAP #" + count + "] pixels(" + w + "x" + h + "):");
                for (int x : xs) for (int y : ys) {
                    sb.append(String.format(" (%d,%d)=%06x", x, y, bi.getRGB(x, y) & 0xFFFFFF));
                }
                logger.info(sb.toString());
            }
        } catch (Exception e) {
            logger.log(Level.WARNING, "采样BufferedImage失败: " + e.getMessage());
        }
    }

    @Override
    public void connect(com.sshtools.javardp.io.IO io, com.sshtools.javardp.CredentialProvider credentialProvider,
            String command, String directory) throws IOException, RdesktopException {
        logger.info("[CONNECT] Starting, securityType=" + stateRef.getSecurityType()
                + ", rdp5=" + stateRef.isRDP5() + ", bpp=" + stateRef.getServerBpp()
                + ", size=" + stateRef.getWidth() + "x" + stateRef.getHeight());
        super.connect(io, credentialProvider, command, directory);
        logger.info("[CONNECT] Done, securityType=" + stateRef.getSecurityType()
                + ", rdp5=" + stateRef.isRDP5() + ", licenceIssued=" + stateRef.isLicenceIssued()
                + ", serverBpp=" + stateRef.getServerBpp());
    }

    @Override
    public void mainLoop() throws IOException, RdesktopException {
        logger.info("[MAINLOOP] Entering main loop, rdp5=" + stateRef.isRDP5());

        if (receiveMethod == null || processPacketMethod == null) {
            logger.warning("[MAINLOOP] 反射方法不可用，使用父类mainLoop");
            super.mainLoop();
            return;
        }

        // 看门狗线程：每10秒检查mainLoop是否卡在receive()
        Thread watchdog = new Thread(() -> {
            try {
                while (!Thread.currentThread().isInterrupted()) {
                    Thread.sleep(10000);
                    long stuckMs = System.currentTimeMillis() - lastReceiveEnterTime;
                    if (stuckMs > 10000 && lastReceiveEnterTime > 0) {
                        logger.warning(String.format(
                            "[WATCHDOG] mainLoop已卡在receive() %.1f秒, totalPDUs=%d, bitmaps=%d, rdp5=%d, sent=%d, recv=%d, bcInAvailable=%d, active=%b, licenceIssued=%b, lastReason=0x%x, isoInjected=%b, isoRecvCalled=%b, isoError=%s, PDU历史=%s",
                            stuckMs / 1000.0, totalPduCount.get(), bitmapUpdateCount.get(),
                            rdp5PacketCount.get(), RdpTlsFix.RdpTransport.getSendPktCount(),
                            RdpTlsFix.RdpTransport.getRecvPktCount(),
                            RdpTlsFix.RdpTransport.getBcInAvailable(),
                            stateRef.isActive(), stateRef.isLicenceIssued(),
                            stateRef.getLastReason(),
                            RdpIsoFix.isInjected(), RdpIsoFix.isReceiveCalled(),
                            RdpIsoFix.getInjectError(),
                            String.join(",", pduHistory)));
                    }
                }
            } catch (InterruptedException e) { /* 正常退出 */ }
        }, "RDP-Watchdog");
        watchdog.setDaemon(true);
        watchdog.start();

        int[] type = new int[1];
        com.sshtools.javardp.Packet data;
        while (true) {
            // 调用private receive()
            data = null;
            lastReceiveEnterTime = System.currentTimeMillis();
            try {
                data = (com.sshtools.javardp.Packet) receiveMethod.invoke(this, (Object) type);
                if (data == null) {
                    logger.info("[MAINLOOP] receive() returned null, exiting");
                    return;
                }
            } catch (java.lang.reflect.InvocationTargetException e) {
                Throwable cause = e.getCause();
                if (cause instanceof EOFException) {
                    logger.info("[MAINLOOP] EOF, exiting");
                    return;
                }
                if (cause instanceof IOException) {
                    logger.log(Level.SEVERE, "[MAINLOOP] IO error after " + totalPduCount.get() + " PDUs: " + cause.getMessage());
                    if (stateRef.getLastReason() > 0)
                        throw new RdesktopDisconnectException(stateRef.getLastReason());
                    else
                        throw new RdesktopDisconnectException(0, (IOException) cause);
                }
                if (cause instanceof RdesktopException) throw (RdesktopException) cause;
                if (cause instanceof RuntimeException) throw (RuntimeException) cause;
                throw new RdesktopException("receive failed: " + cause.getMessage(), cause);
            } catch (Exception e) {
                throw new RdesktopException("reflective receive failed: " + e.getMessage(), e);
            }

            int pduCount = totalPduCount.incrementAndGet();
            int pduType = type[0];

            // 诊断：输出PDU数据和stream状态
            String pduName;
            switch (pduType) {
            case 1: pduName = "DEMAND_ACTIVE"; break;
            case 6: pduName = "DEACTIVATE_ALL"; break;
            case 7: pduName = "DATA"; break;
            case 0: pduName = "KEEPALIVE"; break;
            default: pduName = "UNKNOWN(" + pduType + ")"; break;
            }
            int dataAvail = data.getEnd() - data.getPosition();
            // 对DATA PDU(type=7)，解析子类型(shareDataHeader中的dataType)
            String dataSubType = "";
            if (pduType == 7 && dataAvail >= 9) {
                int savePos = data.getPosition();
                data.incrementPosition(6); // skip shareid(4)+pad(1)+streamid(1)
                data.getLittleEndian16(); // len
                int dataType = data.get8();
                data.setPosition(savePos);
                dataSubType = " subType=" + dataType;
                switch (dataType) {
                    case 0: dataSubType += "(UPDATE)"; break;
                    case 2: dataSubType += "(UPDATE_BITMAP)"; break; 
                    case 3: dataSubType += "(PALETTE)"; break;
                    case 20: dataSubType += "(CONTROL)"; break;
                    case 27: dataSubType += "(POINTER)"; break;
                    case 31: dataSubType += "(SYNCHRONISE)"; break;
                    case 33: dataSubType += "(REFRESH_RECT)"; break;
                    case 34: dataSubType += "(PLAY_SOUND)"; break;
                    case 36: dataSubType += "(SUPPRESS_OUTPUT)"; break;
                    case 37: dataSubType += "(SAVE_SESSION_INFO)"; break;
                    case 38: dataSubType += "(FONTLIST)"; break;
                    case 39: dataSubType += "(FONTMAP)"; break;
                    case 40: dataSubType += "(SET_KEYBOARD_INDICATORS)"; break;
                    case 47: dataSubType += "(SET_ERROR_INFO)"; break;
                    default: break;
                }
            }
            pduHistory.add(String.format("#%d:%s%s", pduCount, pduName, dataSubType.isEmpty() ? "" : dataSubType.split("=")[1].replace(")", "").replace("(", "")));
            logger.info(String.format("[MAINLOOP] PDU #%d: type=%s(%d)%s, dataSize=%d",
                    pduCount, pduName, pduType, dataSubType, dataAvail));

            // DEMAND_ACTIVE处理后，记录关键状态
            if (pduType == 1) {
                logger.info(String.format("[CAPS] serverBpp=%d, width=%d, height=%d, rdp5=%b, serverChannelId=%d, shareId=%d",
                        stateRef.getServerBpp(), stateRef.getWidth(), stateRef.getHeight(),
                        stateRef.isRDP5(), stateRef.getServerChannelId(), stateRef.getShareId()));
            }

            // 诊断：输出data的前16字节hex
            if (dataAvail > 0) {
                int savePos = data.getPosition();
                int dumpLen = Math.min(dataAvail, 32);
                StringBuilder hexSb = new StringBuilder("[MAINLOOP] PDU #" + pduCount + " hex:");
                for (int i = 0; i < dumpLen; i++) {
                    hexSb.append(String.format(" %02x", data.get8()));
                }
                data.setPosition(savePos);
                logger.info(hexSb.toString());
            }

            // 调用private processPacket()
            // DEMAND_ACTIVE(type=1)会触发processDemandActive，内部接收4个PDU(SYNCHRONIZE/COOPERATE/GRANT_CONTROL/FONT_MAP)
            // 如果服务器不发送这些PDU，processPacket会阻塞在这里
            long processStart = System.currentTimeMillis();
            try {
                processPacketMethod.invoke(this, (Object) type, data);
            } catch (java.lang.reflect.InvocationTargetException e) {
                Throwable cause = e.getCause();
                if (cause instanceof RdesktopException) {
                    logger.log(Level.SEVERE, String.format("[MAINLOOP] processPacket error at PDU #%d: %s", pduCount, cause.getMessage()));
                    throw (RdesktopException) cause;
                }
                if (cause instanceof IOException) throw (IOException) cause;
                if (cause instanceof OrderException) throw new RdesktopException(cause.getMessage(), cause);
                if (cause instanceof RuntimeException) throw (RuntimeException) cause;
                throw new RdesktopException("processPacket failed: " + cause.getMessage(), cause);
            } catch (Exception e) {
                throw new RdesktopException("reflective processPacket failed: " + e.getMessage(), e);
            }
            long processMs = System.currentTimeMillis() - processStart;
            if (processMs > 100) {
                logger.info(String.format("[MAINLOOP] PDU #%d (%s) processPacket耗时 %dms", pduCount, pduName, processMs));
            }

            // DEMAND_ACTIVE处理后，发送REFRESH_RECT请求全屏刷新
            // 注意：之前尝试发送SUPPRESS_OUTPUT PDU导致服务器关闭连接(SEND #36断开)
            // 可能是服务器不期望在此时收到此PDU，已移除
            if (pduType == 1 && !refreshSent) {
                refreshSent = true;
                try {
                    logger.info("[REFRESH] 发送TS_REFRESH_RECT_PDU请求全屏刷新");
                    com.sshtools.javardp.Packet refreshData = (com.sshtools.javardp.Packet) initDataMethod.invoke(this, 4);
                    refreshData.set8(0); // numAreas = 0 (全屏刷新)
                    refreshData.set8(0); // pad
                    refreshData.setLittleEndian16(0); // pad
                    refreshData.markEnd(); // 关键：必须调用markEnd设置数据结束位置
                    sendDataMethod.invoke(this, refreshData, 33); // 33 = PDUTYPE2_REFRESH_RECT
                    logger.info("[REFRESH] 刷新请求已发送");
                } catch (Exception e) {
                    logger.warning("[REFRESH] 发送刷新请求失败: " + e.getMessage());
                }
            }

            // 检测服务器是否发送了错误PDU (RDP_DATA_PDU_SET_ERROR)
            int lastReason = stateRef.getLastReason();
            if (lastReason != 0 && lastReason != lastReasonSeen) {
                lastReasonSeen = lastReason;
                logger.warning("[MAINLOOP] 服务器发送错误PDU! lastReason=0x" + Integer.toHexString(lastReason)
                        + " (" + lastReason + ")");
            }
            // 记录服务器状态
            int serverStatus = stateRef.getServerStatus();
            if (serverStatus != 0 && serverStatus != lastServerStatusSeen) {
                lastServerStatusSeen = serverStatus;
                logger.info("[MAINLOOP] 服务器状态变更: serverStatus=0x" + Integer.toHexString(serverStatus));
            }

            // 诊断：processPacket后stream状态
            try {
                if (streamField != null && nextPacketField != null) {
                    Object stream = streamField.get(this);
                    int nextPkt = nextPacketField.getInt(this);
                    if (stream != null) {
                        com.sshtools.javardp.Packet p = (com.sshtools.javardp.Packet) stream;
                        logger.info(String.format("[MAINLOOP] After PDU #%d: stream pos=%d end=%d, next_packet=%d, remaining=%d",
                                pduCount, p.getPosition(), p.getEnd(), nextPkt, p.getEnd() - nextPkt));
                    }
                }
            } catch (Exception e) {
                // 忽略诊断错误
            }

            // DEMAND_ACTIVE处理完毕后，processDemandActive内部已经发送了：
            // sendConfirmActive（含capabilities + ready(INPUT) → doLockKeys同步键状态）
            // sendSynchronize、sendControl、sendFonts，并接收了4个响应PDU。
            // doLockKeys已发送CapsLock/NumLock/ScrollLock同步事件，服务器应开始推送画面。
            // 注意：不再额外发送sendInput(0,0,0,0,0)——RDP_INPUT_SYNCHRONIZE在库中定义为0，
            // 但MS-RDPBCGR规范中INPUT_EVENT_SYNC=3，值0会被服务器当作未知事件忽略。
        }
    }

    public int getBitmapUpdateCount() { return bitmapUpdateCount.get(); }
    public int getRdp5PacketCount() { return rdp5PacketCount.get(); }
    public int getTotalPduCount() { return totalPduCount.get(); }
}
