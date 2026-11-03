package com.skribbl.client;

import com.formdev.flatlaf.FlatIntelliJLaf;

import javax.swing.SwingUtilities;

/**
 * Entry point for Skribbl Client application.
 */
public class ClientMain {

    public static void main(String[] args) {
        FlatIntelliJLaf.setup();

        SwingUtilities.invokeLater(() -> {
            System.out.println("=================================================");
            System.out.println("            SKRIBBL CLIENT INITIALIZED           ");
            System.out.println("=================================================");
        });
    }
}

