package com.nancheung.plugins.jetbrains.legadoreader.presentation.toolwindow.panel;

import com.intellij.icons.AllIcons;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.ui.ComboBox;
import com.intellij.ui.components.JBLabel;
import com.intellij.ui.components.JBPanel;
import com.intellij.util.ui.JBUI;
import com.nancheung.plugins.jetbrains.legadoreader.common.PluginExecutors;
import com.nancheung.plugins.jetbrains.legadoreader.storage.AddressHistoryStorage;
import com.nancheung.plugins.jetbrains.legadoreader.storage.PluginSettingsStorage;
import lombok.extern.slf4j.Slf4j;

import javax.swing.*;
import javax.swing.event.PopupMenuEvent;
import javax.swing.event.PopupMenuListener;
import java.awt.*;
import java.awt.event.ItemEvent;
import java.awt.event.MouseEvent;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * 地址栏面板组件（通用）
 * 负责管理服务器下拉框、添加按钮、刷新按钮和异步加载逻辑
 * <p>
 * 下拉框第一项固定为 {@link #OFFLINE_CACHE_OPTION}（离线），
 * 选择该项时调用 onOfflineMode 回调而非 loadAction；
 * 下拉列表中真实服务器行的右侧渲染"✕"删除标记，点击 ✕ 即可删除该服务器
 * （"离线"为内置虚拟选项，不渲染 ✕，不可删除）。
 *
 * @param <T> 加载结果的数据类型
 * @author NanCheung
 */
@Slf4j
public class AddressBarPanel<T> extends JBPanel<AddressBarPanel<T>> {

    /**
     * 离线模式虚拟选项（非真实服务器地址）
     */
    public static final String OFFLINE_CACHE_OPTION = "离线";

    // ==================== UI 组件 ====================
    private ComboBox<String> addressHistoryBox;
    private JButton addButton;
    private JButton refreshButton;

    // ==================== 数据模型 ====================
    // 面板本身是单例（随 MainReaderPanel 创建），改为实例字段避免静态共享状态
    private final DefaultComboBoxModel<String> ADDRESS_HISTORY_MODEL = new DefaultComboBoxModel<>();

    // ==================== 下拉项 ✕ 删除支持 ====================

    /**
     * 当前下拉弹层列表的引用：由列表渲染器在绘制弹层项时捕获
     * （弹层列表随每次展开重建，渲染时捕获保证引用始终最新）
     */
    private transient JList<?> activePopupList;

    /**
     * ✕ 点击产生的待删除地址（非 null 时抑制选中项自动加载，避免对已删除的失效服务器发起请求）
     */
    private String pendingPopupDelete;

    /**
     * ✕ 删除标记的渲染宽度
     */
    private final int deleteMarkWidth = JBUI.scale(24);

    /**
     * ✕ 点击命中热区宽度（比标记略宽，覆盖 IntelliJ 弹层渲染器为列表项附加的右边距）
     */
    private final int deleteHitZoneWidth = deleteMarkWidth + JBUI.scale(8);

    // ==================== 回调接口 ====================
    private final Supplier<T> loadAction;
    private final Consumer<T> onLoadSucceeded;
    private final Runnable onLoadFailed;
    private final Runnable onOfflineMode;

    /**
     * 标志位：程序设置下拉框选中项时抑制自动加载（避免 refreshHistory 触发 load 循环）
     */
    private boolean suppressAutoLoad = false;

    // ==================== 构造函数 ====================

    /**
     * 创建地址栏面板
     *
     * @param loadAction      在线模式加载动作（在后台线程执行）
     * @param onOfflineMode   选择"离线"时的回调（在 EDT 线程执行）
     * @param onLoadSucceeded 加载成功回调（在 EDT 线程执行）
     * @param onLoadFailed    加载失败回调（在 EDT 线程执行）
     */
    public AddressBarPanel(Supplier<T> loadAction, Runnable onOfflineMode, Consumer<T> onLoadSucceeded, Runnable onLoadFailed) {
        setOpaque(false);
        this.loadAction = loadAction;
        this.onOfflineMode = onOfflineMode;
        this.onLoadSucceeded = onLoadSucceeded;
        this.onLoadFailed = onLoadFailed;
        initializeUI();
        bindEventListeners();
        installPopupDeleteHandler();
    }

    // ==================== UI 创建方法 ====================

    private void initializeUI() {
        setLayout(new BoxLayout(this, BoxLayout.X_AXIS));
        setBorder(JBUI.Borders.empty(4));

        // 服务器下拉框（列表项渲染器支持 ✕ 删除标记）
        addressHistoryBox = new ComboBox<>(ADDRESS_HISTORY_MODEL);
        addressHistoryBox.setName("addressHistoryBox");
        addressHistoryBox.setPreferredSize(JBUI.size(200, -1));
        addressHistoryBox.setMinimumSize(JBUI.size(150, -1));
        addressHistoryBox.setRenderer(new DeletableItemRenderer());

        // 添加按钮
        addButton = new JButton("添加");
        addButton.setName("addButton");
        addButton.setToolTipText("添加新的阅读服务器地址（不带端口自动补 1122）");
        applyTransparentButtonStyle(addButton);

        // 刷新按钮（此前误将文案改成"透明"二字；现恢复文案"刷新"，按钮本体改为透明样式）
        refreshButton = new JButton("刷新");
        refreshButton.setName("refreshButton");
        applyTransparentButtonStyle(refreshButton);

        add(addressHistoryBox);
        add(Box.createHorizontalStrut(JBUI.scale(4)));
        add(addButton);
        add(Box.createHorizontalStrut(JBUI.scale(4)));
        add(refreshButton);
    }

    /**
     * 按钮透明样式：不绘制背景填充与边框，透出背景图，仅保留文字
     */
    private void applyTransparentButtonStyle(JButton button) {
        button.setOpaque(false);
        button.setContentAreaFilled(false);
        button.setBorderPainted(false);
        button.setFocusPainted(false);
    }

    // ==================== 事件绑定方法 ====================

    private void bindEventListeners() {
        refreshButton.addActionListener(e -> load());
        addButton.addActionListener(e -> addServer());

        // 下拉框选择切换：自动加载（程序设置时抑制）
        addressHistoryBox.addItemListener(e -> {
            if (e.getStateChange() == ItemEvent.SELECTED && e.getItem() != null) {
                // ✕ 点击删除过程中的选中（待删除项）不触发加载
                if (pendingPopupDelete == null && !suppressAutoLoad) {
                    load();
                }
            }
        });

        // 弹层收起/取消后清空捕获的弹层列表引用
        addressHistoryBox.addPopupMenuListener(new PopupMenuListener() {
            @Override
            public void popupMenuWillBecomeVisible(PopupMenuEvent e) {
            }

            @Override
            public void popupMenuWillBecomeInvisible(PopupMenuEvent e) {
                activePopupList = null;
            }

            @Override
            public void popupMenuCanceled(PopupMenuEvent e) {
                activePopupList = null;
            }
        });
    }

    // ==================== 业务逻辑方法 ====================

    /**
     * 弹出输入对话框添加服务器
     */
    private void addServer() {
        String input = JOptionPane.showInputDialog(
                SwingUtilities.getWindowAncestor(this),
                "请输入阅读服务器地址：\n（不带端口自动补 1122，如 127.0.0.1）",
                "添加服务器",
                JOptionPane.PLAIN_MESSAGE
        );

        if (input == null || input.trim().isEmpty()) {
            return;
        }

        // 规范化地址（补全协议 + 端口）
        String normalized = AddressHistoryStorage.normalizeAddress(input.trim());
        AddressHistoryStorage.getInstance().addAddress(normalized);

        refreshHistory();
        suppressAutoLoad = true;
        addressHistoryBox.setSelectedItem(normalized);
        suppressAutoLoad = false;

        load();
    }

    /**
     * 删除下拉项 ✕ 标记对应的服务器地址
     * <p>
     * "离线"为内置虚拟选项，渲染器不渲染 ✕，同样不会进入本方法。
     * 删除后：
     * <ul>
     *   <li>删除的是正在使用（或正在显示）的服务器：切换到剩余最近使用的服务器并重新加载，无剩余则回落"离线"；</li>
     *   <li>删除的是其他服务器：仅刷新列表并恢复原显示选中，不触发重载。</li>
     * </ul>
     */
    private void deleteServerFromPopup(String address) {
        if (!AddressHistoryStorage.getInstance().removeAddress(address)) {
            return;
        }
        log.info("已删除服务器地址：{}", address);

        // 记录删除前的显示选中与活动服务器
        String previousDisplay = (String) addressHistoryBox.getSelectedItem();
        boolean wasActiveServer = !AddressHistoryStorage.getInstance().isOfflineMode()
                && address.equals(AddressHistoryStorage.getInstance().getMostRecent());

        refreshHistory();

        if (wasActiveServer || address.equals(previousDisplay)) {
            // 切到剩余最近使用的服务器，无剩余则回落"离线"并重新加载
            String next = AddressHistoryStorage.getInstance().getMostRecent();
            if (next != null) {
                suppressAutoLoad = true;
                addressHistoryBox.setSelectedItem(next);
                suppressAutoLoad = false;
            }
            load();
        } else if (previousDisplay != null
                && (OFFLINE_CACHE_OPTION.equals(previousDisplay) || ADDRESS_HISTORY_MODEL.getIndexOf(previousDisplay) != -1)) {
            // 删除的是非活动服务器：恢复原显示选中，不触发重载
            suppressAutoLoad = true;
            addressHistoryBox.setSelectedItem(previousDisplay);
            suppressAutoLoad = false;
        }
    }

    // ==================== 下拉项 ✕ 删除事件处理 ====================

    /**
     * 安装全局 AWT 鼠标监听，实现下拉项 ✕ 点击删除：
     * <ul>
     *   <li>全局监听器在事件派发到目标组件之前收到事件（见 {@code Component.dispatchEventImpl}），
     *       命中 ✕ 热区时消费事件，可同时阻止弹层默认的"选中该项并收起弹层"行为；</li>
     *   <li>弹层列表引用由 {@link DeletableItemRenderer} 在绘制弹层项时捕获，
     *       通过引用精确匹配，不依赖 IntelliJ 内部 API。</li>
     * </ul>
     */
    private void installPopupDeleteHandler() {
        try {
            Toolkit.getDefaultToolkit().addAWTEventListener(event -> {
                // 只处理本下拉弹层列表上的左键释放
                if (!(event instanceof MouseEvent mouseEvent)
                        || mouseEvent.getID() != MouseEvent.MOUSE_RELEASED
                        || !SwingUtilities.isLeftMouseButton(mouseEvent)
                        || mouseEvent.getSource() != activePopupList) {
                    return;
                }

                // 命中检测：单元格右侧 ✕ 热区
                Point point = mouseEvent.getPoint();
                int index = activePopupList.locationToIndex(point);
                if (index < 0) {
                    return;
                }
                Rectangle cellBounds = activePopupList.getCellBounds(index, index);
                if (cellBounds == null || !cellBounds.contains(point)) {
                    return;
                }
                Object element = activePopupList.getModel().getElementAt(index);
                if (!(element instanceof String address) || OFFLINE_CACHE_OPTION.equals(address)) {
                    return;
                }
                if (point.x < cellBounds.x + cellBounds.width - deleteHitZoneWidth) {
                    return;
                }

                // 消费事件：弹层不会执行默认的"选中并收起"，随后主动收起并删除
                mouseEvent.consume();
                pendingPopupDelete = address;
                SwingUtilities.invokeLater(() -> {
                    try {
                        addressHistoryBox.setPopupVisible(false);
                        deleteServerFromPopup(address);
                    } finally {
                        pendingPopupDelete = null;
                    }
                });
            }, AWTEvent.MOUSE_EVENT_MASK);
        } catch (SecurityException e) {
            log.warn("注册全局鼠标监听失败，下拉项 ✕ 删除功能不可用", e);
        }
    }

    /**
     * 下拉列表项渲染器：
     * 真实服务器地址行右侧渲染"✕"删除标记；"离线"行与下拉框闭合状态（index == -1）不渲染标记。
     * 渲染弹层项（index >= 0）时顺带捕获当前弹层列表引用，供 ✕ 命中检测使用。
     */
    private class DeletableItemRenderer implements ListCellRenderer<String> {

        private final JPanel row = new JPanel(new BorderLayout());
        private final JBLabel addressLabel = new JBLabel();
        private final JBLabel deleteMarkLabel = new JBLabel(AllIcons.Actions.Close);

        private DeletableItemRenderer() {
            row.setOpaque(true);
            // ✕ 标记区域固定宽度，与点击热区对齐
            int iconWidth = AllIcons.Actions.Close.getIconWidth();
            int horizontalPadding = Math.max(0, (deleteMarkWidth - iconWidth) / 2);
            deleteMarkLabel.setBorder(JBUI.Borders.empty(0, horizontalPadding, 0, horizontalPadding));
            row.add(addressLabel, BorderLayout.CENTER);
            row.add(deleteMarkLabel, BorderLayout.EAST);
        }

        @Override
        public Component getListCellRendererComponent(JList<? extends String> list, String value, int index, boolean isSelected, boolean cellHasFocus) {
            // 渲染弹层项时捕获弹层列表引用（闭合状态 index == -1，不捕获）
            if (list != null && index >= 0) {
                activePopupList = list;
            }

            boolean showDeleteMark = index >= 0 && value != null && !OFFLINE_CACHE_OPTION.equals(value);
            deleteMarkLabel.setVisible(showDeleteMark);
            addressLabel.setText(value);

            if (list != null) {
                if (isSelected) {
                    row.setBackground(list.getSelectionBackground());
                    addressLabel.setForeground(list.getSelectionForeground());
                } else {
                    row.setBackground(list.getBackground());
                    addressLabel.setForeground(list.getForeground());
                }
            }
            return row;
        }
    }

    // ==================== 加载与刷新方法 ====================

    /**
     * 执行刷新操作
     * 选择"离线"时调用 onOfflineMode，否则走 loadAction
     */
    public void load() {
        String selected = (String) addressHistoryBox.getSelectedItem();

        // 离线模式：不走 API，直接调用回调（loadOfflineBookshelf 内部异步处理）
        if (OFFLINE_CACHE_OPTION.equals(selected)) {
            // 标记全局离线模式：进度同步、内容加载兜底等据此跳过网络请求
            AddressHistoryStorage.getInstance().setOfflineMode(true);
            refreshButton.setEnabled(false);
            onOfflineMode.run();
            refreshButton.setEnabled(true);
            return;
        }

        // 在线模式
        AddressHistoryStorage.getInstance().setOfflineMode(false);
        refreshButton.setEnabled(false);

        if (selected == null || selected.trim().isEmpty()) {
            refreshButton.setEnabled(true);
            onLoadFailed.run();
            return;
        }

        // 更新历史排序（当前地址移到最前）
        AddressHistoryStorage.getInstance().addAddress(selected);
        refreshHistory();

        String current = (String) addressHistoryBox.getSelectedItem();
        CompletableFuture.supplyAsync(loadAction, PluginExecutors.io())
                .handle((result, throwable) -> {
                    ApplicationManager.getApplication().invokeLater(() -> {
                        if (throwable == null) {
                            onLoadSucceeded.accept(result);
                            return;
                        }
                        if (Boolean.TRUE.equals(PluginSettingsStorage.getInstance().getState().enableErrorLog)) {
                            log.error("加载失败", throwable.getCause());
                        }
                        onLoadFailed.run();
                    });
                    return null;
                }).whenComplete((aVoid, throwable) -> ApplicationManager.getApplication().invokeLater(() -> refreshButton.setEnabled(true)));
    }

    /**
     * 刷新历史记录
     * 下拉框第一项固定为"离线"，后面是真实服务器地址
     */
    public void refreshHistory() {
        suppressAutoLoad = true;
        try {
            List<String> history = AddressHistoryStorage.getInstance().getAddressList();

            // 第一项固定为"离线"
            ADDRESS_HISTORY_MODEL.removeAllElements();
            ADDRESS_HISTORY_MODEL.addElement(OFFLINE_CACHE_OPTION);

            if (history.isEmpty()) {
                addressHistoryBox.setEnabled(true);
                ADDRESS_HISTORY_MODEL.setSelectedItem(OFFLINE_CACHE_OPTION);
                return;
            }

            ADDRESS_HISTORY_MODEL.addAll(history);
            addressHistoryBox.setEnabled(true);

            // 如果之前选中的是离线，保持选中
            String currentSelection = (String) addressHistoryBox.getSelectedItem();
            if (OFFLINE_CACHE_OPTION.equals(currentSelection)) {
                ADDRESS_HISTORY_MODEL.setSelectedItem(OFFLINE_CACHE_OPTION);
            } else {
                ADDRESS_HISTORY_MODEL.setSelectedItem(history.getFirst());
            }
        } finally {
            suppressAutoLoad = false;
        }
    }
}
