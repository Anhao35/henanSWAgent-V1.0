package cn.edu.ha.secagent.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(name = "research_searches")
public class ResearchSearch extends BaseEntity {
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Column(name = "query_text", nullable = false, length = 1000)
    private String queryText;

    @Column(name = "filters_json", length = 2000)
    private String filtersJson;

    @Column(length = 255)
    private String sources;

    @Column(nullable = false)
    private int resultCount;

    @Column(nullable = false)
    private long durationMs;

    @Column(nullable = false, length = 32)
    private String status;
}
