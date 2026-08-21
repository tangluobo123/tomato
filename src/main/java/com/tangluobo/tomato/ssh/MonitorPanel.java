package com.tangluobo.tomato.ssh;

import javafx.application.Platform;
import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.control.Label;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.paint.Color;
import javafx.scene.text.Font;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.LinkedList;
import java.util.List;
import java.util.Timer;
import java.util.TimerTask;

public class MonitorPanel extends BorderPane {

    private final SSHSession sshSession;
    private Timer timer;

    private Canvas cpuCanvas;
    private Canvas memCanvas;
    private Canvas netCanvas;
    private Canvas ioCanvas;

    private Label cpuLabel;
    private Label memLabel;
    private Label netLabel;
    private Label ioLabel;

    private final List<Double> cpuHistory = new LinkedList<>();
    private final List<Double> memHistory = new LinkedList<>();
    private final List<Double> netInHistory = new LinkedList<>();
    private final List<Double> netOutHistory = new LinkedList<>();
    private final List<Double> ioReadHistory = new LinkedList<>();
    private final List<Double> ioWriteHistory = new LinkedList<>();

    private static final int MAX_HISTORY_SIZE = 3600;
    private static final int CANVAS_HEIGHT = 80;
    private static final int PADDING_LEFT = 35;
    private static final int PADDING_BOTTOM = 20;
    private static final int DISPLAY_POINTS = 60;

    private long lastNetRxBytes = 0;
    private long lastNetTxBytes = 0;
    private long lastIoReadBytes = 0;
    private long lastIoWriteBytes = 0;

    private long startTime;

    private long lastCpuTotal = 0;
    private long lastCpuIdle = 0;
    private static final double SMOOTH_FACTOR = 0.3;

    private double smoothCpu = 0;
    private double smoothMem = 0;
    private double smoothNetIn = 0;
    private double smoothNetOut = 0;
    private double smoothIoRead = 0;
    private double smoothIoWrite = 0;

    public MonitorPanel(SSHSession sshSession) {
        this.sshSession = sshSession;
        initUI();
    }

    private void initUI() {
        setStyle("-fx-background-color: #FFFFFF;");
        // 4个图表(各80px) + topBar + VBox padding/spacing ≈ 400px，确保所有图表完整显示
        setPrefHeight(400);
        setMinHeight(250);

        HBox topBar = new HBox();
        topBar.setStyle("-fx-background-color: #f5f5f5; -fx-padding: 4 8; -fx-alignment: center-left;");

        Label titleLabel = new Label("系统监控");
        titleLabel.setStyle("-fx-font-size: 12px; -fx-font-weight: bold; -fx-text-fill: #333;");

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        Label updateLabel = new Label("每1秒更新");
        updateLabel.setStyle("-fx-font-size: 10px; -fx-text-fill: #888;");

        topBar.getChildren().addAll(titleLabel, spacer, updateLabel);
        setTop(topBar);

        BorderPane contentPane = new BorderPane();

        cpuCanvas = createChartCanvas();
        memCanvas = createChartCanvas();
        netCanvas = createChartCanvas();
        ioCanvas = createChartCanvas();

        cpuLabel = new Label("CPU: --%");
        memLabel = new Label("内存: --%");
        netLabel = new Label("网络: --");
        ioLabel = new Label("IO: --");

        HBox cpuBox = createMetricBox("CPU", cpuLabel, cpuCanvas);
        HBox memBox = createMetricBox("内存", memLabel, memCanvas);
        HBox netBox = createMetricBox("网络", netLabel, netCanvas);
        HBox ioBox = createMetricBox("磁盘IO", ioLabel, ioCanvas);

        javafx.scene.layout.VBox vbox = new javafx.scene.layout.VBox(4);
        vbox.setStyle("-fx-padding: 4;");
        vbox.getChildren().addAll(cpuBox, memBox, netBox, ioBox);

        contentPane.setCenter(vbox);
        setCenter(contentPane);
    }

    private Canvas createChartCanvas() {
        Canvas canvas = new Canvas(400, CANVAS_HEIGHT);
        canvas.widthProperty().addListener((obs, old, val) -> redrawCharts());
        return canvas;
    }

    private HBox createMetricBox(String name, Label valueLabel, Canvas canvas) {
        HBox box = new HBox(8);
        box.setStyle("-fx-padding: 4; -fx-alignment: center-left;");

        Label nameLabel = new Label(name);
        nameLabel.setStyle("-fx-font-size: 11px; -fx-text-fill: #666; -fx-min-width: 50px;");

        valueLabel.setStyle("-fx-font-size: 11px; -fx-font-weight: bold; -fx-text-fill: #333;");

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        HBox.setHgrow(canvas, Priority.ALWAYS);

        box.getChildren().addAll(nameLabel, valueLabel, spacer, canvas);
        return box;
    }

    public void startMonitoring() {
        if (timer != null) {
            timer.cancel();
        }

        startTime = System.currentTimeMillis();

        timer = new Timer("Monitor-Timer", true);
        timer.scheduleAtFixedRate(new TimerTask() {
            @Override
            public void run() {
                fetchMetrics();
            }
        }, 0, 1000);
    }

    public void stopMonitoring() {
        if (timer != null) {
            timer.cancel();
            timer = null;
        }
    }

    private void fetchMetrics() {
        if (sshSession == null || !sshSession.isConnected()) {
            return;
        }

        try {
            String output = executeCommand("cat /proc/stat | head -n 1; echo '---SEP---'; free -m; echo '---SEP---'; cat /proc/net/dev; echo '---SEP---'; cat /proc/diskstats; echo '---SEP---'; iostat -d -x 1 1 2>/dev/null || echo ''");

            String[] parts = output.split("---SEP---");
            double cpu = parseCpuUsage(parts.length > 0 ? parts[0] : "");
            double mem = parseMemoryUsage(parts.length > 1 ? parts[1] : "");
            long netRx = parseNetworkRx(parts.length > 2 ? parts[2] : "");
            long netTx = parseNetworkTx(parts.length > 2 ? parts[2] : "");
            var ref = new Object() {
                long ioRead = parts.length > 3 ? parseDiskStatsRead(parts[3]) : 0;
                long ioWrite = parts.length > 3 ? parseDiskStatsWrite(parts[3]) : 0;
            };
            if (ref.ioRead == 0 && parts.length > 4) {
                ref.ioRead = parseIoRead(parts[4]);
                ref.ioWrite = parseIoWrite(parts[4]);
            }

            Platform.runLater(() -> {
                updateMetrics(cpu, mem, netRx, netTx, ref.ioRead, ref.ioWrite);
            });

        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private String executeCommand(String command) throws Exception {
        com.jcraft.jsch.ChannelExec channel = (com.jcraft.jsch.ChannelExec) sshSession.getJschSession().openChannel("exec");
        channel.setCommand(command);
        InputStream in = channel.getInputStream();
        channel.connect();

        BufferedReader reader = new BufferedReader(new InputStreamReader(in));
        StringBuilder sb = new StringBuilder();
        String line;
        while ((line = reader.readLine()) != null) {
            sb.append(line).append("\n");
        }

        channel.disconnect();
        return sb.toString();
    }

    private double parseCpuUsage(String output) {
        try {
            String[] parts = output.trim().split("\\s+");
            if (parts.length >= 5) {
                long total = 0;
                for (int i = 1; i < parts.length; i++) {
                    total += Long.parseLong(parts[i]);
                }
                long idle = Long.parseLong(parts[4]);

                if (lastCpuTotal > 0) {
                    long totalDiff = total - lastCpuTotal;
                    long idleDiff = idle - lastCpuIdle;
                    if (totalDiff > 0) {
                        double cpu = (1 - (double) idleDiff / totalDiff) * 100;
                        lastCpuTotal = total;
                        lastCpuIdle = idle;
                        return cpu;
                    }
                }

                lastCpuTotal = total;
                lastCpuIdle = idle;
            }
        } catch (Exception e) {}
        return 0;
    }

    private double parseMemoryUsage(String output) {
        try {
            String[] lines = output.split("\n");
            for (String line : lines) {
                if (line.startsWith("Mem:")) {
                    String[] parts = line.trim().split("\\s+");
                    if (parts.length >= 3) {
                        long total = Long.parseLong(parts[1]);
                        long used = Long.parseLong(parts[2]);
                        return (used * 100.0) / total;
                    }
                }
            }
        } catch (Exception e) {}
        return 0;
    }

    private long parseNetworkRx(String output) {
        try {
            String[] lines = output.split("\n");
            for (String line : lines) {
                line = line.trim();
                if (line.startsWith("eth0:") || line.startsWith("ens") || line.startsWith("wlp")) {
                    String[] parts = line.split("\\s+");
                    if (parts.length >= 2) {
                        return Long.parseLong(parts[1]);
                    }
                }
            }
            for (String line : lines) {
                line = line.trim();
                if (line.contains(":")) {
                    String[] parts = line.split("\\s+");
                    if (parts.length >= 2 && !line.startsWith("Inter")) {
                        try {
                            return Long.parseLong(parts[1]);
                        } catch (NumberFormatException ignored) {}
                    }
                }
            }
        } catch (Exception e) {}
        return 0;
    }

    private long parseNetworkTx(String output) {
        try {
            String[] lines = output.split("\n");
            for (String line : lines) {
                line = line.trim();
                if (line.startsWith("eth0:") || line.startsWith("ens") || line.startsWith("wlp")) {
                    String[] parts = line.split("\\s+");
                    if (parts.length >= 10) {
                        return Long.parseLong(parts[9]);
                    }
                }
            }
            for (String line : lines) {
                line = line.trim();
                if (line.contains(":") && !line.startsWith("Inter")) {
                    String[] parts = line.split("\\s+");
                    if (parts.length >= 10) {
                        try {
                            return Long.parseLong(parts[9]);
                        } catch (NumberFormatException ignored) {}
                    }
                }
            }
        } catch (Exception e) {}
        return 0;
    }

    private long parseIoRead(String output) {
        try {
            String[] lines = output.split("\n");
            for (String line : lines) {
                line = line.trim();
                if (line.startsWith("sd") || line.startsWith("nvme")) {
                    String[] parts = line.split("\\s+");
                    if (parts.length >= 6) {
                        return (long) (Double.parseDouble(parts[5]) * 1024);
                    }
                }
            }
        } catch (Exception e) {}
        return 0;
    }

    private long parseIoWrite(String output) {
        try {
            String[] lines = output.split("\n");
            for (String line : lines) {
                line = line.trim();
                if (line.startsWith("sd") || line.startsWith("nvme")) {
                    String[] parts = line.split("\\s+");
                    if (parts.length >= 7) {
                        return (long) (Double.parseDouble(parts[6]) * 1024);
                    }
                }
            }
        } catch (Exception e) {}
        return 0;
    }

    private long parseDiskStatsRead(String output) {
        try {
            String[] lines = output.split("\n");
            long totalRead = 0;
            for (String line : lines) {
                line = line.trim();
                String[] parts = line.split("\\s+");
                if (parts.length >= 14) {
                    try {
                        String devName = parts[2];
                        if (devName.startsWith("sd") || devName.startsWith("nvme")) {
                            totalRead += Long.parseLong(parts[5]);
                        }
                    } catch (NumberFormatException ignored) {}
                }
            }
            return totalRead * 512;
        } catch (Exception e) {}
        return 0;
    }

    private long parseDiskStatsWrite(String output) {
        try {
            String[] lines = output.split("\n");
            long totalWrite = 0;
            for (String line : lines) {
                line = line.trim();
                String[] parts = line.split("\\s+");
                if (parts.length >= 14) {
                    try {
                        String devName = parts[2];
                        if (devName.startsWith("sd") || devName.startsWith("nvme")) {
                            totalWrite += Long.parseLong(parts[9]);
                        }
                    } catch (NumberFormatException ignored) {}
                }
            }
            return totalWrite * 512;
        } catch (Exception e) {}
        return 0;
    }

    private void updateMetrics(double cpu, double mem, long netRx, long netTx, long ioRead, long ioWrite) {
        long netRxDiff = netRx - lastNetRxBytes;
        long netTxDiff = netTx - lastNetTxBytes;
        lastNetRxBytes = netRx;
        lastNetTxBytes = netTx;

        long ioReadDiff = ioRead - lastIoReadBytes;
        long ioWriteDiff = ioWrite - lastIoWriteBytes;
        lastIoReadBytes = ioRead;
        lastIoWriteBytes = ioWrite;

        double netInPct = Math.min(100, (netRxDiff * 100.0) / (1024 * 1024));
        double netOutPct = Math.min(100, (netTxDiff * 100.0) / (1024 * 1024));
        double ioReadPct = Math.min(100, (ioReadDiff * 100.0) / (1024 * 1024));
        double ioWritePct = Math.min(100, (ioWriteDiff * 100.0) / (1024 * 1024));

        if (smoothCpu == 0) {
            smoothCpu = cpu;
            smoothMem = mem;
            smoothNetIn = netInPct;
            smoothNetOut = netOutPct;
            smoothIoRead = ioReadPct;
            smoothIoWrite = ioWritePct;
        } else {
            smoothCpu = smoothCpu * (1 - SMOOTH_FACTOR) + cpu * SMOOTH_FACTOR;
            smoothMem = smoothMem * (1 - SMOOTH_FACTOR) + mem * SMOOTH_FACTOR;
            smoothNetIn = smoothNetIn * (1 - SMOOTH_FACTOR) + netInPct * SMOOTH_FACTOR;
            smoothNetOut = smoothNetOut * (1 - SMOOTH_FACTOR) + netOutPct * SMOOTH_FACTOR;
            smoothIoRead = smoothIoRead * (1 - SMOOTH_FACTOR) + ioReadPct * SMOOTH_FACTOR;
            smoothIoWrite = smoothIoWrite * (1 - SMOOTH_FACTOR) + ioWritePct * SMOOTH_FACTOR;
        }

        cpuLabel.setText(String.format("CPU: %.1f%%", smoothCpu));
        memLabel.setText(String.format("内存: %.1f%%", smoothMem));
        netLabel.setText(String.format("网络: ↓%s/s ↑%s/s", formatBytes(netRxDiff), formatBytes(netTxDiff)));
        ioLabel.setText(String.format("IO: ↓%s/s ↑%s/s", formatBytes(ioReadDiff), formatBytes(ioWriteDiff)));

        addHistory(cpuHistory, smoothCpu);
        addHistory(memHistory, smoothMem);
        addHistory(netInHistory, smoothNetIn);
        addHistory(netOutHistory, smoothNetOut);
        addHistory(ioReadHistory, smoothIoRead);
        addHistory(ioWriteHistory, smoothIoWrite);

        redrawCharts();
    }

    private void addHistory(List<Double> history, double value) {
        history.add(value);
        if (history.size() > MAX_HISTORY_SIZE) {
            history.remove(0);
        }
    }

    private String formatBytes(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return String.format("%.1f KB", bytes / 1024.0);
        return String.format("%.1f MB", bytes / (1024.0 * 1024));
    }

    private void redrawCharts() {
        drawLineChart(cpuCanvas, cpuHistory, Color.valueOf("#FF6B6B"));
        drawLineChart(memCanvas, memHistory, Color.valueOf("#4ECDC4"));
        drawDoubleLineChart(netCanvas, netInHistory, netOutHistory, Color.valueOf("#45B7D1"), Color.valueOf("#96CEB4"));
        drawDoubleLineChart(ioCanvas, ioReadHistory, ioWriteHistory, Color.valueOf("#9B59B6"), Color.valueOf("#E74C3C"));
    }

    private String formatTimeLabel(long elapsed, long timestamp) {
        java.text.SimpleDateFormat sdf = new java.text.SimpleDateFormat("mm:ss");
        return sdf.format(new java.util.Date(timestamp));
    }

    private void drawLineChart(Canvas canvas, List<Double> data, Color color) {
        GraphicsContext gc = canvas.getGraphicsContext2D();
        double width = canvas.getWidth();
        double height = CANVAS_HEIGHT;

        gc.clearRect(0, 0, width, height);

        if (data.isEmpty()) return;

        gc.setFont(new Font("SansSerif", 9));

        gc.setStroke(Color.valueOf("#cccccc"));
        gc.setLineWidth(1);
        gc.strokeLine(PADDING_LEFT, 2, PADDING_LEFT, height - PADDING_BOTTOM);
        gc.strokeLine(PADDING_LEFT, height - PADDING_BOTTOM, width - 2, height - PADDING_BOTTOM);

        gc.setStroke(Color.valueOf("#e0e0e0"));
        gc.setLineWidth(0.5);
        double chartWidth = width - PADDING_LEFT - 4;
        double chartHeight = height - PADDING_BOTTOM - 4;
        for (int i = 0; i <= 4; i++) {
            double y = 2 + chartHeight * i / 4;
            gc.strokeLine(PADDING_LEFT, y, width - 2, y);

            gc.setFill(Color.valueOf("#888888"));
            String label = (int) (100 - i * 25) + "%";
            double textWidth = new javafx.scene.text.Text(label).getLayoutBounds().getWidth();
            gc.fillText(label, PADDING_LEFT - textWidth - 4, y + 3);
        }

        long now = System.currentTimeMillis();
        long elapsed = now - startTime;
        for (int i = 0; i <= 4; i++) {
            double x = PADDING_LEFT + chartWidth * i / 4;
            gc.strokeLine(x, height - PADDING_BOTTOM, x, 2);

            gc.setFill(Color.valueOf("#888888"));
            long timestamp = startTime + elapsed * i / 4;
            String label = formatTimeLabel(elapsed, timestamp);
            double textWidth = new javafx.scene.text.Text(label).getLayoutBounds().getWidth();
            gc.fillText(label, x - textWidth / 2, height - 4);
        }

        gc.setStroke(color);
        gc.setLineWidth(2);

        int dataSize = data.size();
        double stepX = chartWidth / Math.max(1, dataSize - 1);

        gc.beginPath();
        boolean first = true;
        for (int i = 0; i < dataSize; i++) {
            double x = PADDING_LEFT + i * stepX;
            double y = 2 + (1 - data.get(i) / 100.0) * chartHeight;
            y = Math.max(2, Math.min(height - PADDING_BOTTOM - 1, y));

            if (first) {
                gc.moveTo(x, y);
                first = false;
            } else {
                gc.lineTo(x, y);
            }
        }
        gc.stroke();

        gc.setFill(color);
        double lastX = PADDING_LEFT + (dataSize - 1) * stepX;
        double lastY = 2 + (1 - data.get(dataSize - 1) / 100.0) * chartHeight;
        lastY = Math.max(2, Math.min(height - PADDING_BOTTOM - 1, lastY));
        gc.fillOval(lastX - 3, lastY - 3, 6, 6);
    }

    private void drawDoubleLineChart(Canvas canvas, List<Double> data1, List<Double> data2, Color color1, Color color2) {
        GraphicsContext gc = canvas.getGraphicsContext2D();
        double width = canvas.getWidth();
        double height = CANVAS_HEIGHT;

        gc.clearRect(0, 0, width, height);

        if (data1.isEmpty()) return;

        gc.setFont(new Font("SansSerif", 9));

        gc.setStroke(Color.valueOf("#cccccc"));
        gc.setLineWidth(1);
        gc.strokeLine(PADDING_LEFT, 2, PADDING_LEFT, height - PADDING_BOTTOM);
        gc.strokeLine(PADDING_LEFT, height - PADDING_BOTTOM, width - 2, height - PADDING_BOTTOM);

        gc.setStroke(Color.valueOf("#e0e0e0"));
        gc.setLineWidth(0.5);
        double chartWidth = width - PADDING_LEFT - 4;
        double chartHeight = height - PADDING_BOTTOM - 4;
        for (int i = 0; i <= 4; i++) {
            double y = 2 + chartHeight * i / 4;
            gc.strokeLine(PADDING_LEFT, y, width - 2, y);

            gc.setFill(Color.valueOf("#888888"));
            String label = (int) (100 - i * 25) + "%";
            double textWidth = new javafx.scene.text.Text(label).getLayoutBounds().getWidth();
            gc.fillText(label, PADDING_LEFT - textWidth - 4, y + 3);
        }

        long now = System.currentTimeMillis();
        long elapsed = now - startTime;
        for (int i = 0; i <= 4; i++) {
            double x = PADDING_LEFT + chartWidth * i / 4;
            gc.strokeLine(x, height - PADDING_BOTTOM, x, 2);

            gc.setFill(Color.valueOf("#888888"));
            long timestamp = startTime + elapsed * i / 4;
            String label = formatTimeLabel(elapsed, timestamp);
            double textWidth = new javafx.scene.text.Text(label).getLayoutBounds().getWidth();
            gc.fillText(label, x - textWidth / 2, height - 4);
        }

        int dataSize = data1.size();
        double stepX = chartWidth / Math.max(1, dataSize - 1);

        gc.setStroke(color1);
        gc.setLineWidth(2);
        gc.beginPath();
        boolean first = true;
        for (int i = 0; i < dataSize; i++) {
            double x = PADDING_LEFT + i * stepX;
            double y = 2 + (1 - data1.get(i) / 100.0) * chartHeight;
            y = Math.max(2, Math.min(height - PADDING_BOTTOM - 1, y));
            if (first) {
                gc.moveTo(x, y);
                first = false;
            } else {
                gc.lineTo(x, y);
            }
        }
        gc.stroke();

        gc.setStroke(color2);
        gc.beginPath();
        first = true;
        for (int i = 0; i < dataSize; i++) {
            double x = PADDING_LEFT + i * stepX;
            double y = 2 + (1 - data2.get(i) / 100.0) * chartHeight;
            y = Math.max(2, Math.min(height - PADDING_BOTTOM - 1, y));
            if (first) {
                gc.moveTo(x, y);
                first = false;
            } else {
                gc.lineTo(x, y);
            }
        }
        gc.stroke();

        if (!data1.isEmpty()) {
            gc.setFill(color1);
            double lastX = PADDING_LEFT + (dataSize - 1) * stepX;
            double lastY = 2 + (1 - data1.get(dataSize - 1) / 100.0) * chartHeight;
            lastY = Math.max(2, Math.min(height - PADDING_BOTTOM - 1, lastY));
            gc.fillOval(lastX - 3, lastY - 3, 6, 6);

            gc.setFill(color2);
            lastY = 2 + (1 - data2.get(dataSize - 1) / 100.0) * chartHeight;
            lastY = Math.max(2, Math.min(height - PADDING_BOTTOM - 1, lastY));
            gc.fillOval(lastX - 3, lastY - 3, 6, 6);
        }
    }
}