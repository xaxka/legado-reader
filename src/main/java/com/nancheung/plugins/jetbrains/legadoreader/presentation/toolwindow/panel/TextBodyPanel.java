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
import javax.swing.border.Border;
import javax.swing.text.BadLocationException;
import java.awt.*;
import java.awt.event.ComponentAdapter;
import java.awt.event.ComponentEvent;
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

    // ==================== 相位对齐几何 ====================
    /** 滚动面板原始边框（动态相位底边距在其内侧叠加） */
    private final Border baseScrollPaneBorder;
    /** 当前为相位对齐牺牲的底部像素（动态底边距，0 = 未牺牲） */
    private int phaseBottomInset = 0;

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
        baseScrollPaneBorder = textScrollPane.getBorder();
        // 视口尺寸变化（工具窗缩放）后重算相位几何（动态底边距 + 首行上边距）
        textScrollPane.getViewport().addComponentListener(new ComponentAdapter() {
            @Override
            public void componentResized(ComponentEvent e) {
                // 延迟到当前布局完成之后，避免在布局回调中改边框/边距
                SwingUtilities.invokeLater(TextBodyPanel.this::adjustPhaseGeometry);
            }
        });
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
        // 正文变更后重算相位几何（布局完成后异步执行）
        SwingUtilities.invokeLater(this::adjustPhaseGeometry);
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
     * <p>
     * 在文档底部之外再预留一个视口高度的滚动余量：否则末页的相位对齐滚动目标
     * 会超过 maxScroll（= viewSize - extent）被 clamp 回文档底部，末页重显
     * 上一页已读的整行（与"多一行"同类问题）。余量只放开滚动上限，
     * 文本底部之下是空白，正常翻页流程（canPageDown / pageDown 返回 -1）
     * 不受影响。
     */
    private void ensureViewSizeAccurate(JViewport viewport) {
        int totalLen = textBodyPane.getDocument().getLength();
        if (totalLen <= 0) return;
        try {
            Rectangle lastRect = textBodyPane.modelToView2D(totalLen - 1).getBounds();
            if (lastRect == null) return;
            int docBottom = lastRect.y + lastRect.height;
            // 文档底 + 一个视口高度余量（供末页相位对齐滚动）
            int target = docBottom + viewport.getExtentSize().height;
            Dimension viewSize = viewport.getViewSize();
            if (viewSize.height < target) {
                viewport.setViewSize(new Dimension(viewSize.width, target));
            }
        } catch (BadLocationException e) {
            // 忽略无效位置
        }
    }

    /**
     * 相位对齐滚动：将 newTop 行滚动为新一页首行，并令视口底部落在行间隙内
     * （或行文字底部），使每页恰好装下整数行完整文字。
     * <p>
     * 关键实测几何（Swing 源码 + 墨迹像素扫描验证）：modelToView2D 返回的行矩形
     * 高度是<b>整行高度</b>（文字高 + 行间隙；行间隙加在行的底部内边距上，文字顶
     * 对齐行顶）。因此："行文字高"须用 FontMetrics 实测（{@link #measureTextHeight()}），
     * "行距"用相邻行矩形 y 之差实测（{@link #measureLinePitch()}，含行间隙），
     * 行间隙 = 行距 - 文字高。
     * <p>
     * 相位修正量 s = min((视口高 - 行文字高) mod 行距, 行间隙)，新视口顶部 =
     * 目标行文字顶 - s。直接取 mod 余数会把新视口顶部切进上一行（已读行）的文字，
     * 故必须再收敛进行间隙内。行间隙容不下相位差时（视口高度与行距极端错位，
     * 配合 {@link #adjustPhaseGeometry()} 的动态底边距后不再出现）s 收敛为行间隙宽，
     * 页底最多残留少量截断，截断行照常由下一页承接完整显示。
     *
     * @param newTop     新一页首行的行首字符偏移
     * @param viewHeight 视口高度
     * @param minY       允许的最小滚动位置 y（向下翻页传 当前视口顶+1 保证推进；向上翻页传 0）
     */
    private void scrollToPageTop(int newTop, int viewHeight, int minY) {
        JViewport viewport = textScrollPane.getViewport();
        // 确保 viewSize 反映文本实际渲染高度，避免 setViewPosition 被 clamp 到错误范围
        ensureViewSizeAccurate(viewport);
        try {
            int totalLen = textBodyPane.getDocument().getLength();
            Rectangle topRect = textBodyPane.modelToView2D(newTop).getBounds();
            if (topRect == null) {
                // 无法定位目标行，退化为行首对齐滚动
                scrollToPosition(newTop);
                return;
            }

            // 布局实测：行距（相邻行矩形 y 之差，含行间隙）与行文字高（FontMetrics，不含行间隙）
            int linePitch = measureLinePitch(newTop, totalLen, topRect);
            int lineTextHeight = measureTextHeight();
            if (lineTextHeight <= 0) {
                // 字体度量不可用时退化为矩形高度（含行距 → 间隙按 0 处理）
                lineTextHeight = topRect.height;
            }
            int lineGap = Math.max(0, linePitch - lineTextHeight);

            // 相位修正量：(视口高 - 行文字高) mod 行距，再收敛进行间隙内
            int phase = Math.max(0, viewHeight - lineTextHeight) % Math.max(1, linePitch);
            phase = Math.min(phase, lineGap);

            // 新视口顶部 = 目标行文字顶 - 相位修正量
            int y = Math.max(topRect.y - phase, minY);
            viewport.setViewPosition(new Point(0, y));
        } catch (BadLocationException e) {
            // 度量失败，退化为行首对齐滚动
            scrollToPosition(newTop);
        }
    }

    /**
     * 向下翻一页（按视口高度逐行计算）
     * <p>
     * 新视口顶部 = 当前视口底部第一行"文字未完整显示"的行首，判定规则：
     * <ul>
     *   <li>modelToView2D 行矩形高度是<b>整行高度</b>（文字高 + 行间隙；行间隙加在行的
     *       底部内边距上、文字顶对齐行顶——Swing 源码与墨迹像素实测验证），因此判定
     *       "文字完整显示"须按 FontMetrics 文字高（{@link #measureTextHeight()}）：
     *       行文字底 = 行矩形顶 + 文字高；</li>
     *   <li>行文字底超出视口底部：文字只显示了一部分（如 20%）或完全不可见——
     *       文字必须显示全才能被翻过去，该行整体带到下一页，作为新视口顶部行；</li>
     *   <li>行文字底在视口内（被截断的至多是行尾间隙）：
     *       文字已完整显示，该行可以翻过去，新视口从下一行开始（零重叠）。</li>
     * </ul>
     * 保证至少推进一行，避免原地不动
     * <p>
     * 相位对齐（{@link #scrollToPageTop}）：滚动时应用相位修正量，使视口底部落在
     * 行间隙内，每页装下整数行完整文字；配合 {@link #adjustPhaseGeometry()} 的
     * 动态底边距，任何视口高度下都不再出现页底截断残行与"已完整显示的行被
     * 带到下一页重看"的问题。
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

            // 找到 viewBottom 对应的行（行矩形含行间隙，文字高由 FontMetrics 实测）
            int pos = findPositionAtY(viewBottom);
            Rectangle rect = textBodyPane.modelToView2D(pos).getBounds();
            if (rect == null) return -1;
            int textHeight = measureTextHeight();
            if (textHeight <= 0) textHeight = rect.height;

            int lineStart = findLineStart(pos);
            int newTop;
            if (rect.y + textHeight <= viewBottom) {
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
            if (newTopRect == null || newTopRect.y + textHeight <= viewBottom) {
                return -1;
            }

            // 保证视口向下推进：新视口顶部必须低于当前顶部，
            // 否则（视口高度不足一行等退化场景）强制推进到下一行
            if (newTopRect.y <= viewTop) {
                newTop = findNextLineStartAfter(newTop, totalLen);
                if (newTop >= totalLen) return -1;
            }

            // 相位对齐滚动：新视口顶部 = newTop 行文字顶 - 相位修正量，
            // 视口底部落在行间隙内，每页装下整数行完整文字
            scrollToPageTop(newTop, viewHeight, viewTop + 1);
            setCaretPosition(newTop);
            return newTop;
        } catch (BadLocationException e) {
            return -1;
        }
    }

    /**
     * 向上翻一页（按视口高度逐行计算）
     * 新视口顶部 = 从当前顶部向上数一个视口高度处的行首
     * 并与 {@link #scrollToPageTop} 相同的相位对齐，保证向上翻页每页也是整数行完整文字
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

        // 对齐到该行行首，并以与向下翻页相同的相位滚动
        int lineStart = findLineStart(pos);

        scrollToPageTop(lineStart, viewHeight, 0);
        setCaretPosition(lineStart);
        return lineStart;
    }

    /**
     * 检查是否还能继续向下翻页
     * <p>
     * 行矩形含行间隙，最后一行"文字底"须按 FontMetrics 文字高判定：
     * 只有文字真正延伸到视口外才算还有未读内容
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
            int textHeight = measureTextHeight();
            if (textHeight <= 0) textHeight = lastRect.height;
            return (lastRect.y + textHeight) > viewBottom;
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

    /**
     * 实测行距（相邻两行文字顶的像素差，含行间隙）
     * <p>
     * 优先测目标行与下一行文字顶之差；目标行是文档最后一行时，
     * 回退测上一行与目标行文字顶之差；均不可测时按行距 = 行文字高退化
     *
     * @param lineStart 目标行的行首字符偏移
     * @param totalLen  文档总长度
     * @param lineRect  目标行的文字矩形
     * @return 实测行距（至少为 1）
     */
    private int measureLinePitch(int lineStart, int totalLen, Rectangle lineRect) {
        try {
            int nextLine = findNextLineStartAfter(lineStart, totalLen);
            if (nextLine < totalLen) {
                Rectangle nextRect = textBodyPane.modelToView2D(nextLine).getBounds();
                if (nextRect != null && nextRect.y > lineRect.y) {
                    return nextRect.y - lineRect.y;
                }
            }
            if (lineStart > 0) {
                int prevLine = findLineStart(lineStart - 1);
                if (prevLine < lineStart) {
                    Rectangle prevRect = textBodyPane.modelToView2D(prevLine).getBounds();
                    if (prevRect != null && prevRect.y < lineRect.y) {
                        return lineRect.y - prevRect.y;
                    }
                }
            }
        } catch (BadLocationException ignored) {
            // 度量失败走退化逻辑
        }
        return Math.max(1, lineRect.height);
    }

    /**
     * 实测行文字高（字形部分的像素高度，不含行间隙）
     * <p>
     * modelToView2D 行矩形高度是整行高度（文字高 + 行间隙），不能直接当文字高使用。
     * 正文为单一字体，行内字形高度 = 字体 FontMetrics 高度（ParagraphView 的行间距
     * = 行内容高 × lineSpacing，加在行的底部内边距上，行内容高即 FontMetrics 高度）。
     *
     * @return 行文字高（像素）；无法测量时返回 -1
     */
    private int measureTextHeight() {
        Font font = textBodyPane.getFont();
        if (font == null) return -1;
        FontMetrics fm = textBodyPane.getFontMetrics(font);
        return fm == null ? -1 : fm.getHeight();
    }

    /**
     * 相位几何调整：让视口与行距"整除对齐"，使每一页（含章节首页）都恰好
     * 装下整数行完整文字。
     * <p>
     * 视口高度通常不是行距的整数倍，且行间隙（行距 - 文字高）有限，仅靠滚动无法
     * 让视口顶部不切上一行、底部不切下一行同时满足。此处通过两处布局微调补齐：
     * <ul>
     *   <li>动态底边距（滚动面板空边框）：牺牲 δ = max(0, r - 2×行间隙) 像素
     *       （δ ≤ 行间隙），使有效视口高的相位余数 r_eff = r - δ ≤ 2×行间隙，
     *       相位修正量 s = min(r_eff, 行间隙) 便能让视口底部落在行间隙内；
     *       空边框不绘制，透出父组件背景，兼容阅读背景图；</li>
     *   <li>首行上边距（textBodyPane 顶边距 = s）：章节首页（视口在文档最顶，
     *       无法上移相位）与后续各页的第一行保持相同的视觉偏移。</li>
     * </ul>
     * 触发时机：视口尺寸变化（工具窗缩放）、正文/样式变更后异步执行；
     * 度量失败时 δ 置 0（退化为纯滚动相位对齐，页底可能出现少量截断）。
     */
    private void adjustPhaseGeometry() {
        JViewport viewport = textScrollPane.getViewport();
        int rawHeight = viewport.getExtentSize().height + phaseBottomInset;
        if (rawHeight <= 0) {
            applyPhaseBottomInset(0);
            return;
        }
        try {
            int totalLen = textBodyPane.getDocument().getLength();
            if (totalLen == 0) {
                applyPhaseBottomInset(0);
                return;
            }
            int textHeight = measureTextHeight();
            Rectangle firstRect = textBodyPane.modelToView2D(0).getBounds();
            if (textHeight <= 0 || firstRect == null) {
                applyPhaseBottomInset(0);
                return;
            }
            int linePitch = measureLinePitch(0, totalLen, firstRect);
            if (linePitch <= textHeight) {
                // 行距异常（无行间隙或度量失败），无法相位对齐
                applyPhaseBottomInset(0);
                return;
            }
            int gap = linePitch - textHeight;
            int r = Math.max(0, rawHeight - textHeight) % linePitch;
            int delta = Math.max(0, r - 2 * gap);
            applyPhaseBottomInset(delta);

            // 首行上边距 = 相位修正量，使章节首页也整页对齐
            int effectiveHeight = rawHeight - delta;
            int rEff = Math.max(0, effectiveHeight - textHeight) % linePitch;
            int phase = Math.min(rEff, gap);
            if (phase != firstRect.y) {
                Insets margin = textBodyPane.getMargin();
                if (margin != null) {
                    textBodyPane.setMargin(new Insets(margin.top + (phase - firstRect.y),
                            margin.left, margin.bottom, margin.right));
                } else {
                    Insets insets = textBodyPane.getInsets();
                    textBodyPane.setMargin(new Insets(phase, insets.left, insets.bottom, insets.right));
                }
            }
        } catch (BadLocationException e) {
            applyPhaseBottomInset(0);
        }
    }

    /**
     * 设置相位对齐的动态底边距（幂等：值未变化时不改动）
     *
     * @param inset 牺牲的底部像素（0 = 还原原始边框）
     */
    private void applyPhaseBottomInset(int inset) {
        if (inset == phaseBottomInset) return;
        phaseBottomInset = inset;
        // 空边框不绘制、透出父组件背景（兼容背景图），叠加在原始边框内侧
        textScrollPane.setBorder(inset > 0
                ? BorderFactory.createCompoundBorder(baseScrollPaneBorder,
                        BorderFactory.createEmptyBorder(0, 0, inset, 0))
                : baseScrollPaneBorder);
        textScrollPane.revalidate();
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
        // 字体/行高变更会改变行距与文字高，重算相位几何
        SwingUtilities.invokeLater(this::adjustPhaseGeometry);
    }

    /**
     * 从设置中应用样式
     */
    public void applyStyleFromSettings() {
        textBodyStyling.applyFromSettings(textBodyPane);
        SwingUtilities.invokeLater(this::adjustPhaseGeometry);
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
