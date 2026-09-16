package com.nancheung.plugins.jetbrains.legadoreader.presentation.toolwindow.panel;

import com.intellij.openapi.ui.TypingTarget;
import com.intellij.openapi.util.ActionCallback;
import com.intellij.ui.JBColor;
import com.intellij.ui.components.JBLabel;
import com.intellij.ui.components.JBPanel;
import com.intellij.ui.components.JBScrollPane;
import com.nancheung.plugins.jetbrains.legadoreader.command.Command;
import com.nancheung.plugins.jetbrains.legadoreader.command.CommandBus;
import com.nancheung.plugins.jetbrains.legadoreader.command.CommandType;
import com.nancheung.plugins.jetbrains.legadoreader.command.payload.NavigateChapterPayload;
import com.nancheung.plugins.jetbrains.legadoreader.presentation.toolwindow.styling.TextBodyStyling;
import lombok.extern.slf4j.Slf4j;

import javax.swing.*;
import javax.swing.text.BadLocationException;
import java.awt.*;
import java.awt.event.KeyEvent;
import javax.swing.ScrollPaneConstants;

/**
 * 正文面板组件
 * 负责显示章节内容和错误提示
 * （返回/上下章等操作按钮已移至 ToolWindow 标题栏右侧）
 *
 * @author NanCheung
 */
@Slf4j
public class TextBodyPanel extends JBPanel<TextBodyPanel> {

    // ==================== 卡片常量 ====================
    private static final String CARD_CONTENT = "CONTENT";
    private static final String CARD_ERROR = "ERROR";

    // ==================== 错误提示文本 ====================
    private static final String ERROR_MESSAGE = """
            请求内容失败，请检查web服务是否开启、url是否正确、网络是否正常？

            小提示：可以在File -> Settings -> Tools -> Legado Reader中进行更多设置哦~
            """;

    // ==================== UI 组件 ====================
    private final JTextPane textBodyPane;
    private final JBScrollPane textScrollPane;
    private final JBPanel<?> textBodyContentPanel;
    private final CardLayout textBodyContentLayout;
    private boolean contentVisible = false;

    // ==================== 样式管理器 ====================
    private final TextBodyStyling textBodyStyling;

    // ==================== 构造函数 ====================
    public TextBodyPanel() {
        super(new BorderLayout());
        this.textBodyStyling = new TextBodyStyling();
        setOpaque(false);

        // 1. 中央内容区（使用 CardLayout）
        textBodyContentLayout = new CardLayout();
        textBodyContentPanel = new JBPanel<>(textBodyContentLayout);
        textBodyContentPanel.setOpaque(false);

        // 1.1 内容卡片：正文
        // 使用 ReaderTextPane：实现 TypingTarget 接口，绕过 JBViewport.doLayout
        // 在 VERTICAL_SCROLLBAR_NEVER 模式下强制 viewPosition.y=0 的重置（翻页失效根因）。
        // 同时重写 getScrollableTracksViewportHeight() 返回 false，避免视图被拉伸适配视口。
        textBodyPane = new ReaderTextPane();
        textBodyPane.setEditable(false);
        textBodyPane.setFocusable(false); // 不可聚焦 → 不显示光标
        textBodyPane.setOpaque(false); // 透明背景，翻页重绘时不填充白色，避免闪烁遮挡 IDE 背景图

        textScrollPane = new JBScrollPane(textBodyPane);
        textScrollPane.setOpaque(false);
        textScrollPane.getViewport().setOpaque(false);
        textBodyContentPanel.add(textScrollPane, CARD_CONTENT);

        // 1.2 错误卡片
        textBodyContentPanel.add(wrapCentered(createErrorLabel()), CARD_ERROR);

        this.add(textBodyContentPanel, BorderLayout.CENTER);

        // 面板自身可聚焦（用于方向键快捷键），textBodyPane 不聚焦（避免光标）
        setFocusable(true);

        // 默认显示内容
        showContent();

        // 注册方向键快捷键（通过 KeyEventDispatcher，焦点在面板内时生效）
        registerArrowKeyDispatcher();
    }

    /**
     * 注册方向键全局监听器，仅在焦点位于本面板内时响应
     * ↑ 上一页  |  ↓ 下一页  |  ← 上一章  |  → 下一章
     */
    private void registerArrowKeyDispatcher() {
        KeyboardFocusManager.getCurrentKeyboardFocusManager().addKeyEventDispatcher(e -> {
            if (e.getID() != KeyEvent.KEY_PRESSED) return false;
            // 焦点不在本面板内则不处理（失去焦点自动失效）
            if (!isFocusInsidePanel()) return false;

            switch (e.getKeyCode()) {
                case KeyEvent.VK_UP:
                    doPageUp();
                    return true;
                case KeyEvent.VK_DOWN:
                    doPageDown();
                    return true;
                case KeyEvent.VK_LEFT:
                    CommandBus.getInstance().dispatchAsync(Command.of(CommandType.PREVIOUS_CHAPTER));
                    return true;
                case KeyEvent.VK_RIGHT:
                    CommandBus.getInstance().dispatchAsync(Command.of(CommandType.NEXT_CHAPTER));
                    return true;
                default:
                    return false;
            }
        });
    }

    /**
     * 检查当前焦点是否在本面板内
     */
    private boolean isFocusInsidePanel() {
        Component focusOwner = KeyboardFocusManager.getCurrentKeyboardFocusManager().getFocusOwner();
        if (focusOwner == null) return false;
        // focusOwner 是本面板或本面板的子孙 → true
        return SwingUtilities.isDescendingFrom(focusOwner, this);
    }

    /**
     * 执行上一页：章内翻页，已到顶部则触发上一章
     */
    private void doPageUp() {
        if (canPageUp()) {
            int newPos = pageUp();
            if (newPos >= 0) return;
        }
        // 已到顶部，触发上一章（定位到末尾，与 PreviousPageHandler 行为一致）
        CommandBus.getInstance().dispatchAsync(
                Command.of(CommandType.PREVIOUS_CHAPTER, new NavigateChapterPayload(true)));
    }

    /**
     * 执行下一页：章内翻页，已到底部则触发下一章
     */
    private void doPageDown() {
        if (canPageDown()) {
            int newPos = pageDown();
            if (newPos >= 0) return;
        }
        // 已到底部，触发下一章
        CommandBus.getInstance().dispatchAsync(Command.of(CommandType.NEXT_CHAPTER));
    }

    // ==================== UI 创建辅助方法 ====================

    /**
     * 创建错误标签
     */
    private JBLabel createErrorLabel() {
        JBLabel label = new JBLabel();
        label.setText("<html><center>" + ERROR_MESSAGE.replace("\n", "<br>") + "</center></html>");
        label.setHorizontalAlignment(SwingConstants.CENTER);
        label.setForeground(JBColor.GRAY);
        return label;
    }

    /**
     * 将组件包装在居中面板中
     */
    private JBPanel<?> wrapCentered(JComponent component) {
        JBPanel<?> wrapper = new JBPanel<>(new GridBagLayout());
        wrapper.setOpaque(false);
        wrapper.add(component);
        return wrapper;
    }

    // ==================== 状态切换方法 ====================

    /**
     * 显示正文内容（隐藏错误）
     */
    public void showContent() {
        textBodyContentLayout.show(textBodyContentPanel, CARD_CONTENT);
    }

    /**
     * 显示错误提示（隐藏内容）
     */
    public void showError() {
        textBodyContentLayout.show(textBodyContentPanel, CARD_ERROR);
    }

    // ==================== 内容操作方法 ====================

    /**
     * 设置正文文本
     *
     * @param text 文本内容
     */
    public void setText(String text) {
        textBodyPane.setText(text);
    }

    /**
     * 获取正文文本
     *
     * @return 文本内容
     */
    public String getText() {
        return textBodyPane.getText();
    }

    /**
     * 设置光标位置
     *
     * @param position 光标位置
     */
    public void setCaretPosition(int position) {
        textBodyPane.setCaretPosition(position);
    }

    /**
     * 将光标设置到文档末尾
     */
    public void setCaretPositionToEnd() {
        int length = textBodyPane.getDocument().getLength();
        textBodyPane.setCaretPosition(length);
    }

    /**
     * 无动画覆盖滑动：直接将目标位置推到视口顶部
     *
     * @param position 目标字符偏移
     */
    public void scrollToPosition(int position) {
        JViewport viewport = textScrollPane.getViewport();
        // 确保 viewSize 反映文本实际渲染高度，避免 setViewPosition 被 clamp 到 0
        ensureViewSizeAccurate(viewport);
        if (position <= 0) {
            // 滚动到文档最顶部，直接设为 (0, 0)，避免 margin 影响
            viewport.setViewPosition(new Point(0, 0));
            return;
        }
        try {
            int totalLen = textBodyPane.getDocument().getLength();
            // 滚动到文档末尾：将视口底部对齐到文档末尾
            if (position >= totalLen - 1) {
                Rectangle endRect = textBodyPane.modelToView2D(totalLen - 1).getBounds();
                if (endRect != null) {
                    int viewHeight = viewport.getExtentSize().height;
                    int y = Math.max(0, endRect.y + endRect.height - viewHeight);
                    viewport.setViewPosition(new Point(0, y));
                }
                return;
            }
            Rectangle viewRect = textBodyPane.modelToView2D(position).getBounds();
            if (viewRect == null) return;
            viewport.setViewPosition(new Point(0, viewRect.y));
        } catch (BadLocationException e) {
            // 忽略无效位置
        }
    }

    /**
     * 确保 viewport 的 viewSize 反映文本实际渲染高度
     * <p>
     * JTextPane 的 getPreferredSize()（由 TextUI 基于 View 首选宽度计算）
     * 可能与按视口宽度换行后的实际布局高度不一致——preferredSize 偏小时，
     * ScrollPaneLayout 设置的 viewSize.height ≤ extentSize.height，
     * 导致 setViewPosition 被 clamp 到 0，视口纹丝不动、翻页失效。
     * 此方法用 modelToView2D 计算文本实际底部，修正 viewSize。
     */
    private void ensureViewSizeAccurate(JViewport viewport) {
        int totalLen = textBodyPane.getDocument().getLength();
        if (totalLen <= 0) return;
        try {
            Rectangle lastRect = textBodyPane.modelToView2D(totalLen - 1).getBounds();
            if (lastRect == null) return;
            int docBottom = lastRect.y + lastRect.height;
            Dimension viewSize = viewport.getViewSize();
            if (viewSize.height < docBottom) {
                viewport.setViewSize(new Dimension(viewSize.width, docBottom));
            }
        } catch (BadLocationException e) {
            // 忽略无效位置
        }
    }

    /**
     * 向下翻一页（按视口高度逐行计算）
     * <p>
     * 新视口顶部 = 当前视口底部第一行"文字未完整显示"的行首，判定规则（减去行间隙）：
     * <ul>
     *   <li>行矩形（modelToView2D 只覆盖文字高度，不含行间隙）底部超出视口底部：
     *       文字只显示了一部分（如 20%）或完全不可见——文字必须显示全才能被翻过去，
     *       该行整体带到下一页，作为新视口顶部行；</li>
     *   <li>行矩形底部在视口内（被截断的至多是行间隙）：
     *       文字已完整显示，该行可以翻过去，新视口从下一行开始（零重叠）。</li>
     * </ul>
     * 保证至少推进一行，避免原地不动
     *
     * @return 跳转后的行首字符偏移；已到底则返回 -1
     */
    public int pageDown() {
        JViewport viewport = textScrollPane.getViewport();
        int viewHeight = viewport.getExtentSize().height;
        if (viewHeight <= 0) return -1;
        int viewTop = viewport.getViewPosition().y;
        int viewBottom = viewTop + viewHeight;

        try {
            int totalLen = textBodyPane.getDocument().getLength();
            if (totalLen == 0) return -1;

            // 找到 viewBottom 对应的行，rect 为该行的文字矩形（不含行间隙）
            int pos = findPositionAtY(viewBottom);
            Rectangle rect = textBodyPane.modelToView2D(pos).getBounds();
            if (rect == null) return -1;

            int lineStart = findLineStart(pos);
            int newTop;
            if (rect.y + rect.height <= viewBottom) {
                // 该行文字已完整显示（被截断的至多是行间隙）→ 该行可以翻过去，
                // 新视口从下一行开始（零重叠）
                newTop = findNextLineStartAfter(lineStart, totalLen);
            } else {
                // 该行文字未完整显示（部分可见，如只显示 20%，或行首已在视口底部之下）：
                // 文字必须显示全才能翻过去 → 该行整体作为新视口顶部行
                newTop = lineStart;
            }

            // newTop 已超出文档末尾 → 到底
            if (newTop >= totalLen) return -1;

            // 关键：newTop 所在行的文字必须在当前视口中未完整显示，
            // 如果已完整显示（剩余内容不足一页），不滚动，返回 -1 触发下一章
            Rectangle newTopRect = textBodyPane.modelToView2D(newTop).getBounds();
            if (newTopRect == null || newTopRect.y + newTopRect.height <= viewBottom) {
                return -1;
            }

            // 保证视口向下推进：新视口顶部必须低于当前顶部，
            // 否则（视口高度不足一行等退化场景）强制推进到下一行
            if (newTopRect.y <= viewTop) {
                newTop = findNextLineStartAfter(newTop, totalLen);
                if (newTop >= totalLen) return -1;
            }

            scrollToPosition(newTop);
            setCaretPosition(newTop);
            return newTop;
        } catch (BadLocationException e) {
            return -1;
        }
    }

    /**
     * 向上翻一页（按视口高度逐行计算）
     * 新视口顶部 = 从当前顶部向上数一个视口高度处的行首
     * 对齐到行首，避免截断
     *
     * @return 跳转后的行首字符偏移；已到顶则返回 -1
     */
    public int pageUp() {
        JViewport viewport = textScrollPane.getViewport();
        int viewHeight = viewport.getExtentSize().height;
        int viewTop = viewport.getViewPosition().y;
        int totalLen = textBodyPane.getDocument().getLength();

        if (totalLen == 0) return -1;

        // 目标位置：从当前 viewport 顶部往上翻一个 viewport 高度
        int targetY = Math.max(0, viewTop - viewHeight);
        int pos = findPositionAtY(targetY);

        if (pos <= 0) {
            // 已到文档顶部，滚动到文档开头
            // 返回 0 表示成功（不触发上一章），上一章由 canPageUp() 为 false 时触发
            scrollToPosition(0);
            setCaretPosition(0);
            return 0;
        }

        // 对齐到该行行首
        int lineStart = findLineStart(pos);

        scrollToPosition(lineStart);
        setCaretPosition(lineStart);
        return lineStart;
    }

    /**
     * 检查是否还能继续向下翻页
     */
    public boolean canPageDown() {
        JViewport viewport = textScrollPane.getViewport();
        int viewHeight = viewport.getExtentSize().height;
        if (viewHeight <= 0) return false;
        Rectangle viewRect = viewport.getViewRect();
        int viewBottom = viewRect.y + viewHeight;

        try {
            int totalLen = textBodyPane.getDocument().getLength();
            if (totalLen == 0) return false;
            Rectangle lastRect = textBodyPane.modelToView2D(totalLen - 1).getBounds();
            if (lastRect == null) return false;
            return (lastRect.y + lastRect.height) > viewBottom;
        } catch (BadLocationException e) {
            return false;
        }
    }

    /**
     * 检查是否还能继续向上翻页
     */
    public boolean canPageUp() {
        return textScrollPane.getViewport().getViewPosition().y > 0;
    }

    // ==================== 内部工具方法 ====================

    /**
     * 根据像素 Y 坐标找到对应的字符偏移
     */
    private int findPositionAtY(float y) {
        int pos = textBodyPane.viewToModel2D(new Point(0, (int) y));
        if (pos >= 0) return pos;
        // viewToModel2D 返回 -1 时，y 在文档范围之外
        // y <= 0 说明在文档上方，返回 0（首字符）
        return y <= 0 ? 0 : textBodyPane.getDocument().getLength() - 1;
    }

    /**
     * 找到包含指定位置的那一行的行首字符偏移
     *
     * @param pos 文档中的任意字符偏移
     * @return 该行行首的字符偏移
     */
    private int findLineStart(int pos) {
        if (pos <= 0) return 0;
        try {
            Rectangle rect = textBodyPane.modelToView2D(pos).getBounds();
            if (rect == null) return pos;
            int lineY = rect.y;

            // 二分查找行首：文本布局的 y 坐标随偏移单调不减，
            // 找 [0, pos] 内最小的 y == lineY 的偏移（O(log n) 次布局查询，替代逐字符回扫）
            int lo = 0, hi = pos;
            while (lo < hi) {
                int mid = (lo + hi) >>> 1;
                Rectangle r = textBodyPane.modelToView2D(mid).getBounds();
                if (r == null || r.y < lineY) {
                    lo = mid + 1;
                } else {
                    hi = mid;
                }
            }
            return lo;
        } catch (BadLocationException e) {
            return pos;
        }
    }

    /**
     * 从当前行首往后找下一行开头
     *
     * @param lineStart 当前行的行首字符偏移
     * @param totalLen  文档总长度
     * @return 下一行的行首字符偏移，若无则返回 totalLen
     */
    private int findNextLineStartAfter(int lineStart, int totalLen) {
        try {
            Rectangle currentRect = textBodyPane.modelToView2D(lineStart).getBounds();
            if (currentRect == null) return totalLen;
            int currentY = currentRect.y;

            // 二分查找下一行行首：y 随偏移单调不减，
            // 找 (lineStart, totalLen) 内最小的 y > currentY 的偏移（O(log n) 次布局查询，替代逐字符扫描）
            int lo = lineStart + 1, hi = totalLen;
            while (lo < hi) {
                int mid = (lo + hi) >>> 1;
                Rectangle r = textBodyPane.modelToView2D(mid).getBounds();
                if (r == null || r.y <= currentY) {
                    lo = mid + 1;
                } else {
                    hi = mid;
                }
            }
            return lo;
        } catch (BadLocationException ignored) {}
        return totalLen;
    }

    // ==================== 样式操作方法 ====================

    /**
     * 应用样式
     *
     * @param fontColor  字体颜色
     * @param font       字体
     * @param lineHeight 行高
     */
    public void applyStyle(JBColor fontColor, Font font, double lineHeight) {
        textBodyStyling.apply(textBodyPane, fontColor, font, lineHeight);
    }

    /**
     * 从设置中应用样式
     */
    public void applyStyleFromSettings() {
        textBodyStyling.applyFromSettings(textBodyPane);
    }

    // ==================== 查询方法 ====================

    /**
     * 设置滚动条显隐
     * <p>
     * 使用 {@code VERTICAL_SCROLLBAR_NEVER} 策略真正隐藏滚动条。
     * {@link com.intellij.ui.components.JBViewport} 的 {@code doLayout} 在 NEVER 模式下
     * 会强制 {@code viewPosition.y=0}，但 {@link ReaderTextPane} 实现了 {@link TypingTarget}
     * 接口，{@code doLayout} 中的 {@code view instanceof TypingTarget} 检查为 true，
     * 跳过强制重置，{@code setViewPosition} 正常工作。
     *
     * @param hide true 隐藏滚动条，false 显示滚动条
     */
    public void setScrollBarVisible(boolean hide) {
        textScrollPane.setVerticalScrollBarPolicy(
                hide ? ScrollPaneConstants.VERTICAL_SCROLLBAR_NEVER
                     : ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED
        );
    }

    /**
     * 获取当前是否处于可见（正文）状态
     *
     * @return true 如果内容可见
     */
    public boolean isContentVisible() {
        return contentVisible;
    }

    /**
     * 设置面板的可见状态（由 MainReaderPanel 控制）
     */
    public void setContentVisible(boolean visible) {
        this.contentVisible = visible;
    }

    /**
     * 自定义 JTextPane，实现 {@link TypingTarget} 接口。
     * <p>
     * IntelliJ 的 {@link com.intellij.ui.components.JBViewport#doLayout} 在检测到
     * {@code VERTICAL_SCROLLBAR_NEVER} 策略时会强制 {@code viewPosition.y=0}，
     * 但会跳过实现了 {@code TypingTarget} 的组件（{@code view instanceof TypingTarget}）。
     * 实现此接口后，隐藏滚动条时 {@code setViewPosition} 不再被重置，翻页正常工作。
     * {@code type} 方法返回 {@code null}（本组件只读，不处理输入）。
     */
    private static class ReaderTextPane extends JTextPane implements TypingTarget {
        @Override
        public boolean getScrollableTracksViewportHeight() {
            return false;
        }

        @Override
        public ActionCallback type(String text) {
            return null;
        }
    }
}
