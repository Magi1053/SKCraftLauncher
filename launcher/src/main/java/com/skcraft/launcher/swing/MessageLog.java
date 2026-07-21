/*
 * SK's Minecraft Launcher
 * Copyright (C) 2010-2014 Albert Pham <http://www.sk89q.com> and contributors
 * Please see LICENSE.txt for license information.
 */

package com.skcraft.launcher.swing;

import com.skcraft.launcher.launch.GameLogBuffer;
import com.skcraft.launcher.launch.GameLogLevel;
import com.skcraft.launcher.launch.GameLogLine;
import com.skcraft.launcher.util.LimitLinesDocumentListener;
import com.skcraft.launcher.util.LogBuffer;
import com.skcraft.launcher.util.SimpleLogFormatter;

import javax.swing.*;
import javax.swing.text.*;
import java.awt.*;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import static org.apache.commons.io.IOUtils.closeQuietly;

/**
 * A simple message log.
 */
public class MessageLog extends JPanel {

    private static final Logger rootLogger = Logger.getLogger("");

    // In game mode this mirrors the external ring capacity; the document listener
    // is disabled.
    private final int numLines;
    private final boolean colorEnabled;
    private final boolean lineWrap;
    private final boolean limitLinesInDocument;
    private final boolean gameMode;

    protected JTextComponent textComponent;
    protected Document document;
    private JScrollPane scrollText;
    private GameLogBuffer gameLogBuffer;
    private Runnable gameLogDirtyCallback;

    private Handler loggerHandler;
    protected final SimpleAttributeSet defaultAttributes = new SimpleAttributeSet();
    protected final SimpleAttributeSet highlightedAttributes;
    protected final SimpleAttributeSet warnAttributes;
    protected final SimpleAttributeSet errorAttributes;
    protected final SimpleAttributeSet fatalAttributes;
    protected final SimpleAttributeSet infoAttributes;
    protected final SimpleAttributeSet debugAttributes;
    protected final SimpleAttributeSet traceAttributes;

    public MessageLog(int numLines, boolean colorEnabled) {
        this(numLines, colorEnabled, true, true);
    }

    public MessageLog(int numLines, boolean colorEnabled, boolean lineWrap) {
        this(numLines, colorEnabled, lineWrap, true);
    }

    public MessageLog(int numLines, boolean colorEnabled, boolean lineWrap, boolean limitLinesInDocument) {
        this(numLines, colorEnabled, lineWrap, limitLinesInDocument, false);
    }

    private MessageLog(int numLines, boolean colorEnabled, boolean lineWrap,
            boolean limitLinesInDocument, boolean gameMode) {
        this.numLines = numLines;
        this.colorEnabled = colorEnabled;
        this.lineWrap = lineWrap;
        this.limitLinesInDocument = limitLinesInDocument;
        this.gameMode = gameMode;

        this.highlightedAttributes = new SimpleAttributeSet();
        StyleConstants.setForeground(highlightedAttributes, new Color(0xFF7F00));
        this.warnAttributes = new SimpleAttributeSet();
        StyleConstants.setForeground(warnAttributes, new Color(0xFF7F00));
        this.errorAttributes = new SimpleAttributeSet();
        StyleConstants.setForeground(errorAttributes, new Color(0xFF3300));
        this.fatalAttributes = new SimpleAttributeSet();
        StyleConstants.setForeground(fatalAttributes, new Color(0xC00000));
        this.infoAttributes = new SimpleAttributeSet();
        this.debugAttributes = new SimpleAttributeSet();
        StyleConstants.setForeground(debugAttributes, new Color(0x5B9BD5));
        this.traceAttributes = new SimpleAttributeSet();
        StyleConstants.setForeground(traceAttributes, new Color(0xDDEBF7));

        setLayout(new BorderLayout());

        initComponents();
    }

    public static MessageLog createGameLog(int numLines) {
        return new MessageLog(numLines, true, false, false, true);
    }

    private void initComponents() {
        if (colorEnabled) {
            JTextPane text = new JTextPane() {
                @Override
                public boolean getScrollableTracksViewportWidth() {
                    return lineWrap;
                }
            };
            this.textComponent = text;
        } else {
            JTextArea text = new JTextArea();
            this.textComponent = text;
            text.setLineWrap(lineWrap);
            text.setWrapStyleWord(lineWrap);
        }

        textComponent.setFont(new JLabel().getFont());
        textComponent.setEditable(false);
        textComponent.setComponentPopupMenu(TextFieldPopupMenu.INSTANCE);
        DefaultCaret caret = (DefaultCaret) textComponent.getCaret();
        caret.setUpdatePolicy(DefaultCaret.NEVER_UPDATE);
        document = textComponent.getDocument();
        if (limitLinesInDocument) {
            document.addDocumentListener(new LimitLinesDocumentListener(numLines, true));
        }

        scrollText = new JScrollPane(textComponent);
        scrollText.setBorder(null);
        scrollText.setVerticalScrollBarPolicy(
                ScrollPaneConstants.VERTICAL_SCROLLBAR_ALWAYS);
        scrollText.setHorizontalScrollBarPolicy(
                lineWrap ? ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER
                        : ScrollPaneConstants.HORIZONTAL_SCROLLBAR_AS_NEEDED);

        add(scrollText, BorderLayout.CENTER);
    }

    public String getPastableText() {
        String text = textComponent.getText().replaceAll("[\r\n]+", "\n");
        text = text.replaceAll("Session ID is [A-Fa-f0-9]+", "Session ID is [redacted]");
        return text;
    }

    public void clear() {
        if (gameMode) {
            runOnEventDispatchThread(() -> {
                if (gameLogBuffer != null) {
                    gameLogBuffer.clear();
                }
                textComponent.setText("");
            });
        } else {
            textComponent.setText("");
        }
    }

    public void bindGameBuffer(GameLogBuffer buffer, Runnable onDirty) {
        if (!gameMode) {
            throw new IllegalStateException("A game log buffer can only be bound in game mode");
        }
        if (!SwingUtilities.isEventDispatchThread()) {
            throw new IllegalStateException("A game log buffer must be bound on the EDT");
        }

        gameLogBuffer = buffer;
        gameLogDirtyCallback = onDirty;
        textComponent.setText("");
        applyBufferDelta(buffer);
    }

    public void refreshFromBuffer(GameLogBuffer buffer) {
        if (!gameMode) {
            throw new IllegalStateException("A game log buffer can only be refreshed in game mode");
        }

        runOnEventDispatchThread(() -> {
            if (gameLogBuffer != buffer) {
                throw new IllegalStateException("The game log buffer has not been bound");
            } else {
                applyBufferDelta(buffer);
            }
        });
    }

    public void setTailContent(final String text) {
        if (gameMode) {
            runOnEventDispatchThread(() -> {
                gameLogBuffer.clear();
                appendPlainLines(splitLines(text));
                applyBufferDelta(gameLogBuffer);
            });
            return;
        }

        if (SwingUtilities.isEventDispatchThread()) {
            textComponent.setText(text);
            scrollToLatest();
            return;
        }

        SwingUtilities.invokeLater(() -> {
            textComponent.setText(text);
            scrollToLatest();
        });
    }

    /**
     * Log a message given the {@link javax.swing.text.AttributeSet}.
     * 
     * @param line       line
     * @param attributes attribute set, or null for none
     */
    public void log(final String line, AttributeSet attributes) {
        if (gameMode) {
            appendPlainLines(splitLines(line));
            gameLogDirtyCallback.run();
            return;
        }

        final Document d = document;
        if (colorEnabled) {
            if (line.startsWith("(!!)")) {
                attributes = highlightedAttributes;
            }
        }
        final AttributeSet a = attributes;

        SwingUtilities.invokeLater(() -> {
            try {
                int offset = d.getLength();
                d.insertString(offset, line,
                        (a != null && colorEnabled) ? a : defaultAttributes);
                scrollToLatest();
            } catch (BadLocationException ble) {

            }
        });
    }

    private void scrollToLatest() {
        if (lineWrap) {
            textComponent.setCaretPosition(textComponent.getDocument().getLength());
            return;
        }

        if (scrollText == null) {
            return;
        }

        JScrollBar vertical = scrollText.getVerticalScrollBar();
        if (vertical != null) {
            vertical.setValue(vertical.getMaximum());
        }
    }

    private void applyBufferDelta(GameLogBuffer buffer) {
        boolean autoFollow = isPinnedToBottom();
        JScrollBar horizontal = scrollText != null ? scrollText.getHorizontalScrollBar() : null;
        int horizontalValue = horizontal != null ? horizontal.getValue() : 0;
        int selectionStart = textComponent.getSelectionStart();
        int selectionEnd = textComponent.getSelectionEnd();
        GameLogBuffer.SyncDelta delta = buffer.drainDelta();
        if (delta.getDropped() == 0 && delta.getAppended().isEmpty()) {
            return;
        }

        try {
            int removedLength = removeLeadingLines(delta.getDropped());
            appendLines(delta.getAppended());

            if (!autoFollow) {
                int documentLength = document.getLength();
                int adjustedStart = Math.min(documentLength, Math.max(0, selectionStart - removedLength));
                int adjustedEnd = Math.min(documentLength, Math.max(0, selectionEnd - removedLength));
                textComponent.select(adjustedStart, adjustedEnd);
            }
            schedulePostLayoutScroll(autoFollow, horizontalValue);
        } catch (BadLocationException e) {
            throw new IllegalStateException("Failed to update game log document", e);
        }
    }

    private void schedulePostLayoutScroll(boolean autoFollow, int horizontalValue) {
        JScrollBar vertical = scrollText != null ? scrollText.getVerticalScrollBar() : null;
        int scheduledValue = vertical != null ? vertical.getValue() : 0;
        SwingUtilities.invokeLater(() -> {
            JScrollBar currentVertical = scrollText != null ? scrollText.getVerticalScrollBar() : null;
            boolean userHasNotMovedUp = currentVertical == null || currentVertical.getValue() >= scheduledValue;
            if (autoFollow
                    && userHasNotMovedUp
                    && textComponent.getSelectionStart() == textComponent.getSelectionEnd()) {
                scrollToLatest();
            }
            JScrollBar currentHorizontal = scrollText != null ? scrollText.getHorizontalScrollBar() : null;
            if (currentHorizontal != null) {
                currentHorizontal.setValue(horizontalValue);
            }
        });
    }

    private int removeLeadingLines(int lineCount) throws BadLocationException {
        if (lineCount <= 0 || document.getLength() == 0) {
            return 0;
        }

        Element root = document.getDefaultRootElement();
        int removeEnd = lineCount >= root.getElementCount()
                ? document.getLength()
                : root.getElement(lineCount).getStartOffset();
        document.remove(0, removeEnd);
        return removeEnd;
    }

    private void appendLines(List<GameLogLine> lines) throws BadLocationException {
        if (lines.isEmpty()) {
            return;
        }

        boolean needsSeparator = document.getLength() > 0;
        StringBuilder run = new StringBuilder(lines.size() * 80);
        GameLogLevel runLevel = null;

        for (GameLogLine line : lines) {
            GameLogLevel level = line.getLevel();
            if (runLevel != null && level != runLevel) {
                document.insertString(document.getLength(), run.toString(), attributesFor(runLevel));
                run.setLength(0);
            }
            runLevel = level;

            if (needsSeparator) {
                run.append('\n');
            }
            run.append(line.getText());
            needsSeparator = true;
        }

        if (runLevel != null) {
            document.insertString(document.getLength(), run.toString(), attributesFor(runLevel));
        }
    }

    private AttributeSet attributesFor(GameLogLevel level) {
        switch (level) {
            case FATAL:
                return fatalAttributes;
            case ERROR:
                return errorAttributes;
            case WARN:
                return warnAttributes;
            case DEBUG:
                return debugAttributes;
            case TRACE:
                return traceAttributes;
            case INFO:
            default:
                return defaultAttributes;
        }
    }

    private void appendPlainLines(List<String> lines) {
        for (String line : lines) {
            gameLogBuffer.append(line);
        }
    }

    private boolean isPinnedToBottom() {
        if (textComponent.getSelectionStart() != textComponent.getSelectionEnd()) {
            return false;
        }
        JScrollBar vertical = scrollText != null ? scrollText.getVerticalScrollBar() : null;
        int bottomTolerance = textComponent.getFontMetrics(textComponent.getFont()).getHeight();
        return document.getLength() == 0
                || vertical == null
                || vertical.getMaximum() - vertical.getValue() - vertical.getVisibleAmount() <= bottomTolerance;
    }

    private static List<String> splitLines(String text) {
        List<String> lines = new ArrayList<>();
        if (text == null || text.isEmpty()) {
            return lines;
        }

        String[] split = text.replace("\r", "").split("\n", -1);
        int length = split.length;
        if (length > 0 && split[length - 1].isEmpty()) {
            length--;
        }
        for (int i = 0; i < length; i++) {
            lines.add(split[i]);
        }
        return lines;
    }

    private static void runOnEventDispatchThread(Runnable task) {
        if (SwingUtilities.isEventDispatchThread()) {
            task.run();
        } else {
            SwingUtilities.invokeLater(task);
        }
    }

    /**
     * Get an output stream that can be written to.
     * 
     * @return output stream
     */
    public ConsoleOutputStream getOutputStream() {
        return getOutputStream((AttributeSet) null);
    }

    /**
     * Get an output stream with the given attribute set.
     * 
     * @param attributes attributes
     * @return output stream
     */
    public ConsoleOutputStream getOutputStream(AttributeSet attributes) {
        return new ConsoleOutputStream(attributes);
    }

    /**
     * Get an output stream using the give color.
     * 
     * @param color color to use
     * @return output stream
     */
    public ConsoleOutputStream getOutputStream(Color color) {
        SimpleAttributeSet attributes = new SimpleAttributeSet();
        StyleConstants.setForeground(attributes, color);
        return getOutputStream(attributes);
    }

    /**
     * Consume an input stream and print it to the dialog. The consumer
     * will be in a separate daemon thread.
     * 
     * @param from stream to read
     */
    public void consume(InputStream from) {
        consume(from, getOutputStream());
    }

    /**
     * Consume an input stream and print it to the dialog. The consumer
     * will be in a separate daemon thread.
     * 
     * @param from  stream to read
     * @param color color to use
     */
    public void consume(InputStream from, Color color) {
        consume(from, getOutputStream(color));
    }

    /**
     * Consume an input stream and print it to the dialog. The consumer
     * will be in a separate daemon thread.
     * 
     * @param from       stream to read
     * @param attributes attributes
     */
    public void consume(InputStream from, AttributeSet attributes) {
        consume(from, getOutputStream(attributes));
    }

    /**
     * Internal method to consume a stream.
     * 
     * @param from         stream to consume
     * @param outputStream console stream to write to
     */
    private void consume(InputStream from, ConsoleOutputStream outputStream) {
        final InputStream in = from;
        final PrintWriter out = new PrintWriter(outputStream, true);
        Thread thread = new Thread(() -> {
            byte[] buffer = new byte[1024];
            try {
                int len;
                while ((len = in.read(buffer)) != -1) {
                    String s = new String(buffer, 0, len);
                    out.append(s);
                    out.flush();
                }
            } catch (IOException e) {
            } finally {
                closeQuietly(in);
                closeQuietly(out);
            }
        });
        thread.setDaemon(true);
        thread.start();
    }

    /**
     * Register a global logger listener, replaying buffered history first.
     */
    public void registerLoggerHandler() {
        if (loggerHandler != null) {
            return;
        }

        loggerHandler = new ConsoleLoggerHandler();

        LogBuffer buffer = LogBuffer.get();
        if (buffer != null) {
            for (LogRecord record : buffer.snapshot()) {
                loggerHandler.publish(record);
            }
        }

        rootLogger.addHandler(loggerHandler);
    }

    /**
     * Detach the handler on the global logger.
     */
    public void detachGlobalHandler() {
        if (loggerHandler != null) {
            rootLogger.removeHandler(loggerHandler);
            loggerHandler = null;
        }
    }

    public SimpleAttributeSet asDefault() {
        return defaultAttributes;
    }

    public SimpleAttributeSet asHighlighted() {
        return highlightedAttributes;
    }

    public SimpleAttributeSet asError() {
        return errorAttributes;
    }

    public SimpleAttributeSet asInfo() {
        return infoAttributes;
    }

    public SimpleAttributeSet asDebug() {
        return debugAttributes;
    }

    /**
     * Used to send logger messages to the launcher console.
     * Ignores records that are not from the launcher (or its libraries under
     * com.skcraft).
     */
    private class ConsoleLoggerHandler extends Handler {
        private final SimpleLogFormatter formatter = new SimpleLogFormatter();

        @Override
        public void publish(LogRecord record) {
            if (record == null || !isLoggable(record) || !isLauncherRecord(record)) {
                return;
            }

            Level level = record.getLevel();
            AttributeSet attributes = defaultAttributes;

            if (level.intValue() >= Level.WARNING.intValue()) {
                attributes = errorAttributes;
            } else if (level.intValue() < Level.INFO.intValue()) {
                attributes = debugAttributes;
            }

            log(formatter.format(record), attributes);
        }

        private boolean isLauncherRecord(LogRecord record) {
            String name = record.getLoggerName();
            return name == null || name.startsWith("com.skcraft");
        }

        @Override
        public void flush() {
        }

        @Override
        public void close() throws SecurityException {
        }
    }

    /**
     * Used to send console messages to the console.
     */
    private class ConsoleOutputStream extends ByteArrayOutputStream {
        private AttributeSet attributes;

        private ConsoleOutputStream(AttributeSet attributes) {
            this.attributes = attributes;
        }

        @Override
        public void flush() {
            String data = toString();
            if (data.length() == 0)
                return;
            log(data, attributes);
            reset();
        }
    }

}
