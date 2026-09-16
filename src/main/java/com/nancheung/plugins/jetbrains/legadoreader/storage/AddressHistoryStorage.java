package com.nancheung.plugins.jetbrains.legadoreader.storage;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.components.PersistentStateComponent;
import com.intellij.openapi.components.Service;
import com.intellij.openapi.components.State;
import com.intellij.openapi.components.Storage;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * 地址历史存储服务（Application Service）
 * 使用 IntelliJ Platform 的 PersistentStateComponent 进行持久化
 *
 * @author NanCheung
 */
@Service
@State(name = "LegadoReaderAddressHistory",storages = @Storage("nancheung-legadoReader-addressHistory.xml"))
public final class AddressHistoryStorage implements PersistentStateComponent<AddressHistoryStorage.State> {

    /**
     * 地址协议前缀正则（预编译，normalizeAddress 高频调用）
     */
    private static final Pattern SCHEME_PATTERN = Pattern.compile("^[a-zA-Z][a-zA-Z0-9+.-]*://.*");

    /**
     * 内部状态类，用于 XML 序列化
     * PersistentStateComponent 框架会自动检测字段变化并持久化
     */
    public static class State {
        public List<HistoryItemState> items = new ArrayList<>();
        /**
         * 是否处于离线模式（地址栏选中"离线"）
         */
        public boolean offlineMode = false;
    }

    /**
     * 历史记录项状态类
     */
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class HistoryItemState {
        public String address;
        public long lastAccessTime;
    }

    private State state = new State();

    /**
     * 获取服务实例
     *
     * @return 服务实例
     */
    public static AddressHistoryStorage getInstance() {
        return ApplicationManager.getApplication().getService(AddressHistoryStorage.class);
    }

    @Nullable
    @Override
    public State getState() {
        return state;
    }

    @Override
    public void loadState(@NotNull State state) {
        this.state = state;
    }

    /**
     * 历史记录最大保存数量
     */
    public static final int MAX_SIZE = 4;

    /**
     * 规范化服务器地址：
     * 1. 补全协议前缀（无 http:// 时自动补）
     * 2. 补全端口（host 后无 :port 时自动补 :1122）
     * <p>
     * 示例：
     * <ul>
     *   <li>{@code 127.0.0.1} → {@code http://127.0.0.1:1122}</li>
     *   <li>{@code 127.0.0.1:8080} → {@code http://127.0.0.1:8080}</li>
     *   <li>{@code http://192.168.1.1} → {@code http://192.168.1.1:1122}</li>
     * </ul>
     */
    public static String normalizeAddress(String address) {
        if (address == null) {
            return null;
        }
        String trimmed = address.trim();
        if (trimmed.isEmpty()) {
            return trimmed;
        }

        // 1. 补全协议前缀（预编译正则，避免每次调用都重新编译）
        if (!SCHEME_PATTERN.matcher(trimmed).matches()) {
            trimmed = "http://" + trimmed;
        }

        // 2. 补全端口：提取协议后的 host[:port][/path]，检查是否含端口
        int schemeEnd = trimmed.indexOf("://");
        String scheme = trimmed.substring(0, schemeEnd + 3);
        String rest = trimmed.substring(schemeEnd + 3);

        // rest 可能是 host:port/path 或 host/path 或 host:port 或 host
        String hostPort = rest.indexOf('/') >= 0
                ? rest.substring(0, rest.indexOf('/'))
                : rest;

        if (!hostPort.contains(":")) {
            // 没有端口，在 host 后补 :1122
            trimmed = scheme + hostPort + ":1122" + rest.substring(hostPort.length());
        }

        return trimmed;
    }

    /**
     * 添加地址到历史记录
     * 自动去重、排序、限制数量
     *
     * @param address 地址
     */
    public void addAddress(String address) {
        String normalized = normalizeAddress(address);
        if (normalized == null || normalized.isEmpty()) {
            return;
        }

        State currentState = getState();

        // 移除已存在的相同地址
        currentState.items.removeIf(item -> normalizeAddress(item.address).equals(normalized));

        // 添加到最前面
        HistoryItemState newItem = new HistoryItemState(normalized, System.currentTimeMillis());
        currentState.items.addFirst(newItem);

        // 限制数量
        if (currentState.items.size() > MAX_SIZE) {
            currentState.items.removeLast();
        }
    }

    /**
     * 从历史记录中删除指定的服务器地址（用于清理失效服务器）
     *
     * @param address 待删除的地址（会先规范化；地址栏的"离线"虚拟选项不在此存储中，不受影响）
     * @return true 删除成功；false 地址为空或历史中不存在
     */
    public boolean removeAddress(String address) {
        String normalized = normalizeAddress(address);
        if (normalized == null || normalized.isEmpty()) {
            return false;
        }
        return getState().items.removeIf(item -> normalizeAddress(item.address).equals(normalized));
    }

    /**
     * 获取地址列表（按时间倒序）
     *
     * @return 地址列表
     */
    public List<String> getAddressList() {
        return getState().items.stream()
                .map(item -> normalizeAddress(item.address))
                .collect(Collectors.toList());
    }

    /**
     * 获取最近使用的地址
     *
     * @return 最近使用的地址，如果没有则返回 null
     */
    public String getMostRecent() {
        List<HistoryItemState> items = getState().items;
        return items.isEmpty() ? null : normalizeAddress(items.getFirst().address);
    }

    /**
     * 是否处于离线模式（地址栏选中"离线"）
     * <p>
     * 离线模式下：进度不同步到服务器、章节内容不走 API 兜底（快速失败）、
     * 打开书籍时优先恢复本地保存的阅读进度。
     */
    public boolean isOfflineMode() {
        return getState().offlineMode;
    }

    /**
     * 设置离线模式标志（由地址栏切换选项时调用）
     */
    public void setOfflineMode(boolean offlineMode) {
        getState().offlineMode = offlineMode;
    }
}
