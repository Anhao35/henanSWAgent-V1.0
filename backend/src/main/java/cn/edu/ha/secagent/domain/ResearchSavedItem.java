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
@Table(name = "research_saved_items")
public class ResearchSavedItem extends BaseEntity {
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Column(nullable = false, length = 32)
    private String source;

    @Column(nullable = false, length = 512)
    private String sourceId;

    @Column(nullable = false, length = 32)
    private String itemType;

    @Column(nullable = false, length = 1000)
    private String title;

    @Column(columnDefinition = "TEXT")
    private String authors;

    private Integer publicationYear;

    @Column(length = 500)
    private String venue;

    @Column(columnDefinition = "LONGTEXT")
    private String abstractText;

    @Column(length = 255)
    private String doi;

    @Column(name = "source_url", length = 1500)
    private String sourceUrl;

    @Column(length = 1500)
    private String openAccessUrl;

    private Integer citationCount;
}
