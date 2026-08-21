package com.tangluobo.tomato.module.connect;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * 全局配置管理
 */
public class GlobalConfig {
    private static final String CONFIG_DIR = System.getProperty("user.home") + "/.tomato";
    private static final String CONFIG_FILE = CONFIG_DIR + "/global.json";
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private int scrollbackLines = 1000;

    private String tableFontName = "Sans Serif";
    private int tableFontSize = 10;

    private boolean sidebarVisible = true;

    private String sshTerminalFontName = "monospace";
    private double sshTerminalFontSize = 13.0;

    public int getScrollbackLines() {
        return scrollbackLines;
    }

    public void setScrollbackLines(int scrollbackLines) {
        this.scrollbackLines = scrollbackLines;
    }

    public String getTableFontName() {
        return tableFontName;
    }

    public void setTableFontName(String tableFontName) {
        this.tableFontName = tableFontName;
    }

    public int getTableFontSize() {
        return tableFontSize;
    }

    public void setTableFontSize(int tableFontSize) {
        this.tableFontSize = tableFontSize;
    }

    public boolean isSidebarVisible() {
        return sidebarVisible;
    }

    public void setSidebarVisible(boolean sidebarVisible) {
        this.sidebarVisible = sidebarVisible;
    }

    public String getSshTerminalFontName() {
        return sshTerminalFontName;
    }

    public void setSshTerminalFontName(String sshTerminalFontName) {
        this.sshTerminalFontName = sshTerminalFontName;
    }

    public double getSshTerminalFontSize() {
        return sshTerminalFontSize;
    }

    public void setSshTerminalFontSize(double sshTerminalFontSize) {
        this.sshTerminalFontSize = sshTerminalFontSize;
    }

    private static GlobalConfig instance;

    public static GlobalConfig getInstance() {
        if (instance == null) {
            instance = load();
        }
        return instance;
    }

    private static GlobalConfig load() {
        Path filePath = Paths.get(CONFIG_FILE);
        if (!Files.exists(filePath)) {
            return new GlobalConfig();
        }
        try {
            String content = Files.readString(filePath, StandardCharsets.UTF_8);
            GlobalConfig config = GSON.fromJson(content, GlobalConfig.class);
            return config != null ? config : new GlobalConfig();
        } catch (Exception e) {
            return new GlobalConfig();
        }
    }

    public void save() {
        try {
            Files.createDirectories(Paths.get(CONFIG_DIR));
            String json = GSON.toJson(this);
            Files.writeString(Paths.get(CONFIG_FILE), json, StandardCharsets.UTF_8);
        } catch (IOException e) {
            e.printStackTrace();
        }
    }
}
