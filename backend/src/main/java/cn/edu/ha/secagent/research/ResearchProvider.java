package cn.edu.ha.secagent.research;

import java.util.List;

public interface ResearchProvider {
    String source();
    default boolean available() { return true; }
    boolean supports(String itemType);
    List<ResearchDtos.SearchItem> search(ResearchDtos.SearchRequest request);
}
