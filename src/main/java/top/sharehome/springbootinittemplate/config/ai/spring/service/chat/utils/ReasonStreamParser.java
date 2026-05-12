package top.sharehome.policybackend.ai.service.chat.utils;

import org.apache.commons.lang3.StringUtils;

/**
 * 深度思考流解析器（全程实时流式输出版）
 *
 * @author AntonyCheng
 */
public class ReasonStreamParser {

    // ----------------------------------------------------------------
    // 构造参数
    // ----------------------------------------------------------------

    /**
     * 是否为思维链模式。
     * false → 所有内容实时透传到 reply，isOrphanEndTag 此时无意义。
     */
    private final boolean isReasoningMode;

    /**
     * 是否为孤立结束标签模式（仅在 isReasoningMode=true 时生效）。
     * true  → 流开始即实时输出 think，遇到孤立 {@code </think>} 后切换输出 reply。
     * false → 标准 {@code <think>...</think>} 模式，遇到 {@code <think>} 后实时输出 think，
     *         遇到 {@code </think>} 后切换输出 reply；{@code <think>} 之前的内容输出到 reply。
     */
    private final boolean isOrphanEndTag;

    // ----------------------------------------------------------------
    // 内容缓冲区
    // ----------------------------------------------------------------

    private final StringBuilder thinkContent = new StringBuilder();
    private final StringBuilder replyContent = new StringBuilder();

    // ----------------------------------------------------------------
    // 增量位置追踪（供 getXxxIncrement() 方法使用）
    // ----------------------------------------------------------------

    private int lastRetrievedThinkLength = 0;
    private int lastRetrievedReplyLength = 0;

    // 用于触发增量回调的长度记录
    private int lastThinkContentLength = 0;
    private int lastReplyContentLength = 0;

    // ----------------------------------------------------------------
    // 标准模式状态
    // ----------------------------------------------------------------

    private boolean foundFirstThinkStart = false;
    private boolean thinkTagComplete = false;
    private int thinkTagDepth = 0;

    // ----------------------------------------------------------------
    // 孤立标签模式状态
    // ----------------------------------------------------------------

    private boolean orphanThinkComplete = false;

    // ----------------------------------------------------------------
    // 标签匹配状态机（标准模式 & 孤立标签模式共用）
    // ----------------------------------------------------------------

    private enum TagState {
        NORMAL, MATCHING
    }

    private TagState currentState = TagState.NORMAL;

    /**
     * 标签字符匹配缓冲区，最多暂存 max(START_TAG, END_TAG).length - 1 = 8 个字符，
     * 一旦确认匹配成功或失败立即清空，不会积压正文内容。
     */
    private final StringBuilder tagBuffer = new StringBuilder();

    // ----------------------------------------------------------------
    // 标签常量
    // ----------------------------------------------------------------

    private static final String START_TAG = "<think>";
    private static final String END_TAG = "</think>";

    // ----------------------------------------------------------------
    // 构造方法
    // ----------------------------------------------------------------

    public ReasonStreamParser(boolean isReasoningMode, boolean isOrphanEndTag) {
        this.isReasoningMode = isReasoningMode;
        this.isOrphanEndTag = isReasoningMode && isOrphanEndTag;
    }

    // ----------------------------------------------------------------
    // 公开入口
    // ----------------------------------------------------------------

    /**
     * 处理流式输入的每个数据块
     *
     * @param chunk 接收到的数据块
     */
    public void processChunk(String chunk) {
        if (StringUtils.isEmpty(chunk)) {
            return;
        }
        for (char c : chunk.toCharArray()) {
            processChar(c);
        }
    }

    /**
     * 流结束时调用，将 tagBuffer 中可能残留的字符冲入当前目标缓冲区。
     */
    public void finish() {
        if (!tagBuffer.isEmpty()) {
            flushTagBufferToCurrent();
        }
    }

    // ----------------------------------------------------------------
    // 字符级分发
    // ----------------------------------------------------------------

    private void processChar(char c) {
        if (!isReasoningMode) {
            appendToReply(c);
            return;
        }
        if (isOrphanEndTag) {
            processCharOrphan(c);
        } else {
            processCharStandard(c);
        }
    }

    // ----------------------------------------------------------------
    // 孤立标签模式处理
    // 流开始即实时写入 think，遇到 </think> 后切换写入 reply
    // ----------------------------------------------------------------

    private void processCharOrphan(char c) {
        if (orphanThinkComplete) {
            // 已过 </think>，直接写入 reply
            appendToReply(c);
            return;
        }
        if (currentState == TagState.NORMAL) {
            if (c == '<') {
                tagBuffer.setLength(0);
                tagBuffer.append(c);
                currentState = TagState.MATCHING;
            } else {
                // 直接实时写入 think
                appendToThink(c);
            }
        } else {
            tagBuffer.append(c);
            String buf = tagBuffer.toString();
            if (buf.equals(END_TAG)) {
                // 命中 </think>，切换阶段
                orphanThinkComplete = true;
                thinkTagComplete = true;
                onThinkContentComplete(thinkContent.toString());
                currentState = TagState.NORMAL;
                tagBuffer.setLength(0);
            } else if (!END_TAG.startsWith(buf)) {
                // 不是 </think> 前缀，将 tagBuffer 冲入 think
                flushTagBufferToCurrent();
            }
            // 否则继续等待匹配
        }
    }

    // ----------------------------------------------------------------
    // 标准模式处理
    // <think> 之前内容写入 reply，<think>...</think> 之间实时写入 think，之后写入 reply
    // ----------------------------------------------------------------

    private void processCharStandard(char c) {
        if (thinkTagComplete) {
            appendToReply(c);
            return;
        }
        if (currentState == TagState.NORMAL) {
            if (c == '<') {
                tagBuffer.setLength(0);
                tagBuffer.append(c);
                currentState = TagState.MATCHING;
            } else {
                if (!foundFirstThinkStart) {
                    appendToReply(c);
                } else {
                    appendToThink(c);
                }
            }
        } else {
            tagBuffer.append(c);
            String buf = tagBuffer.toString();
            if (buf.equals(START_TAG)) {
                handleStartTag();
                currentState = TagState.NORMAL;
                tagBuffer.setLength(0);
            } else if (buf.equals(END_TAG)) {
                handleEndTag();
                currentState = TagState.NORMAL;
                tagBuffer.setLength(0);
            } else if (!START_TAG.startsWith(buf) && !END_TAG.startsWith(buf)) {
                // 两个标签的前缀都不匹配，冲出 tagBuffer
                flushTagBufferToCurrent();
            }
            // 否则继续等待匹配
        }
    }

    private void handleStartTag() {
        if (!foundFirstThinkStart) {
            foundFirstThinkStart = true;
            thinkTagDepth = 1;
        } else {
            // 嵌套 <think>，作为正文内容写入 think
            thinkTagDepth++;
            appendStringToThink(START_TAG);
        }
    }

    private void handleEndTag() {
        if (foundFirstThinkStart) {
            thinkTagDepth--;
            if (thinkTagDepth == 0) {
                thinkTagComplete = true;
                onThinkContentComplete(thinkContent.toString());
            } else {
                // 嵌套闭合，作为正文内容写入 think
                appendStringToThink(END_TAG);
            }
        } else {
            // 从未出现过 <think>，</think> 作为普通文本写入 reply
            appendStringToReply(END_TAG);
        }
    }

    // ----------------------------------------------------------------
    // tagBuffer 冲出：根据当前状态决定写入 think 还是 reply
    // ----------------------------------------------------------------

    private void flushTagBufferToCurrent() {
        String buf = tagBuffer.toString();
        tagBuffer.setLength(0);
        currentState = TagState.NORMAL;
        if (isInThinkPhase()) {
            appendStringToThink(buf);
        } else {
            appendStringToReply(buf);
        }
    }

    /**
     * 判断当前是否处于"写入 think"阶段
     */
    private boolean isInThinkPhase() {
        if (isOrphanEndTag) {
            return !orphanThinkComplete;
        }
        return foundFirstThinkStart && !thinkTagComplete;
    }

    // ----------------------------------------------------------------
    // 写入 & 增量回调触发
    // ----------------------------------------------------------------

    private void appendToThink(char c) {
        thinkContent.append(c);
        triggerThinkContentIncrement();
    }

    private void appendStringToThink(String s) {
        thinkContent.append(s);
        triggerThinkContentIncrement();
    }

    private void appendToReply(char c) {
        replyContent.append(c);
        triggerReplyContentIncrement();
    }

    private void appendStringToReply(String s) {
        replyContent.append(s);
        triggerReplyContentIncrement();
    }

    private void triggerThinkContentIncrement() {
        int currentLength = thinkContent.length();
        if (currentLength > lastThinkContentLength) {
            String increment = thinkContent.substring(lastThinkContentLength, currentLength);
            lastThinkContentLength = currentLength;
            onThinkContentIncrement(increment);
        }
    }

    private void triggerReplyContentIncrement() {
        int currentLength = replyContent.length();
        if (currentLength > lastReplyContentLength) {
            String increment = replyContent.substring(lastReplyContentLength, currentLength);
            lastReplyContentLength = currentLength;
            onReplyContentIncrement(increment);
            onReplyContentUpdate(replyContent.toString());
        }
    }

    // ----------------------------------------------------------------
    // 可重写的回调方法
    // ----------------------------------------------------------------

    public void onThinkContentComplete(String thinkContent) {
    }

    public void onThinkContentIncrement(String increment) {
    }

    public void onReplyContentUpdate(String replyContent) {
    }

    public void onReplyContentIncrement(String increment) {
    }

    // ----------------------------------------------------------------
    // Getter 方法
    // ----------------------------------------------------------------

    public String getThinkContent() {
        String res = thinkContent.toString().trim();
        return StringUtils.isNotBlank(res) ? res : StringUtils.EMPTY;
    }

    public String getThinkContentIncrement() {
        int currentLength = thinkContent.length();
        if (currentLength > lastRetrievedThinkLength) {
            String increment = thinkContent.substring(lastRetrievedThinkLength, currentLength);
            lastRetrievedThinkLength = currentLength;
            return StringUtils.isNotBlank(increment) ? increment : StringUtils.EMPTY;
        }
        return StringUtils.EMPTY;
    }

    public String getReplyContent() {
        String res = replyContent.toString();
        return StringUtils.isNotBlank(res) ? res.trim() : StringUtils.EMPTY;
    }

    public String getReplyContentIncrement() {
        int currentLength = replyContent.length();
        if (currentLength > lastRetrievedReplyLength) {
            String increment = replyContent.substring(lastRetrievedReplyLength, currentLength);
            lastRetrievedReplyLength = currentLength;
            return StringUtils.isNotBlank(increment) ? increment : StringUtils.EMPTY;
        }
        return StringUtils.EMPTY;
    }

    public boolean hasThinkContent() {
        if (isOrphanEndTag) {
            return orphanThinkComplete;
        }
        return foundFirstThinkStart;
    }

    public boolean isThinkComplete() {
        if (isOrphanEndTag) {
            return orphanThinkComplete;
        }
        return thinkTagComplete;
    }

    // ----------------------------------------------------------------
    // 重置
    // ----------------------------------------------------------------

    public void reset() {
        thinkContent.setLength(0);
        replyContent.setLength(0);
        lastRetrievedThinkLength = 0;
        lastRetrievedReplyLength = 0;
        lastThinkContentLength = 0;
        lastReplyContentLength = 0;
        foundFirstThinkStart = false;
        thinkTagComplete = false;
        thinkTagDepth = 0;
        orphanThinkComplete = false;
        currentState = TagState.NORMAL;
        tagBuffer.setLength(0);
    }

}