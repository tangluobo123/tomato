package test;

import javafx.application.Application;

public class Launcher {
    public static void main(String[] args) {
        // 启用LCD文字渲染，消除模糊
        System.setProperty("prism.lcdtext", "true");
        System.setProperty("prism.text", "lcd");
        Application.launch(PowerShellTerminal.class, args);
    }
}
