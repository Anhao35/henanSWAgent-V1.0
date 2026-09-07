package cn.edu.ha.secagent.evidence;

public interface EvidenceProvider {
    String source();
    boolean supports(String indicatorType);
    boolean configured();
    EvidenceDtos.Item query(String indicatorType, String indicator);
}
