package cn.edu.ha.secagent.agent;

import cn.edu.ha.secagent.common.ApiException;
import org.springframework.http.HttpStatus;
import java.util.*;
import java.util.regex.Pattern;

/** Only the user's request is classified here, never extracted attachment text. SIR still extracts IOC. */
public final class TaskIntent {
    private TaskIntent() {}
    public static String resolve(String requested,String query) {
        String mode=requested==null?"AUTO":requested.toUpperCase(Locale.ROOT);
        // Compatibility for links and persisted drafts created before knowledge retrieval
        // became an always-on capability of the Dify workflow.
        if("KNOWLEDGE".equals(mode)) mode="AUTO";
        if(!Set.of("AUTO","SECURITY","READ","EXPLAIN").contains(mode))
            throw new ApiException(HttpStatus.BAD_REQUEST,"INVALID_TASK_MODE","不支持的任务模式");
        if(!mode.equals("AUTO")) return mode;
        String text=query==null?"":query;
        boolean read=Pattern.compile("总结|阅读|解读|翻译|论文|文章|summariz|translate|read this",Pattern.CASE_INSENSITIVE).matcher(text).find();
        boolean scan=Pattern.compile("检测|研判|恶意|查杀|信誉|威胁情报|是否安全|scan|reputation",Pattern.CASE_INSENSITIVE).matcher(text).find();
        boolean noScan=Pattern.compile("不要.{0,8}(查询|检测|研判)|不查询|不检测|仅.{0,4}(解释|总结|阅读)").matcher(text).find();
        if(noScan) return read?"READ":"EXPLAIN";
        if(read&&!scan) return "READ";
        if(read&&scan) throw new ApiException(HttpStatus.BAD_REQUEST,"CLARIFY_INTENT","同时检测到阅读与安全研判意图，请选择本轮任务模式；混合任务请分两次提交");
        if(Pattern.compile("https?://",Pattern.CASE_INSENSITIVE).matcher(text).find()&&!scan)
            throw new ApiException(HttpStatus.BAD_REQUEST,"CLARIFY_INTENT","发现链接：请选择“安全研判”或“资料解读”，明确本轮链接用途");
        return "AUTO";
    }
}
