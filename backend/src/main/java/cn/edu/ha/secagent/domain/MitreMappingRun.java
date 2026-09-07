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
@Table(name = "mitre_mapping_runs")
public class MitreMappingRun extends BaseEntity {
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Column(nullable = false, length = 16)
    private String mappingType;

    @Column(nullable = false, columnDefinition = "LONGTEXT")
    private String inputText;

    @Column(nullable = false, length = 64, columnDefinition = "CHAR(64)")
    private String inputHash;

    @Column(columnDefinition = "LONGTEXT")
    private String resultJson;

    @Column(length = 100)
    private String model;

    @Column(nullable = false, length = 32)
    private String status;

    @Column(length = 500)
    private String errorMessage;

    private Integer promptTokens;
    private Integer completionTokens;
}
