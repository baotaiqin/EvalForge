-- EvalForge application database schema.
-- Reconstructed from the SQL statements and row mappings in src/main/java/com/qatools/service.
-- The external Agent/SemiMind/SemiClaw environment schemas are not owned by this project.
-- All statements are idempotent; this file creates missing tables but does not modify existing data.

CREATE TABLE IF NOT EXISTS environment (
    id           VARCHAR(64)  NOT NULL,
    name         VARCHAR(255) NOT NULL,
    url          VARCHAR(1024) NOT NULL,
    agents       LONGTEXT NULL,
    platform     VARCHAR(50)  NOT NULL DEFAULT 'semimind',
    tenant_id    VARCHAR(255) NULL,
    username     VARCHAR(255) NULL,
    password     TEXT NULL,
    ws_cookie    TEXT NULL,
    account_role VARCHAR(20)  NULL,
    created_at   DATETIME NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    KEY idx_environment_url (url(191)),
    KEY idx_environment_created_at (created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS model_config (
    id         VARCHAR(64)  NOT NULL,
    name       VARCHAR(255) NOT NULL,
    provider   VARCHAR(100) NULL,
    base_url   VARCHAR(1024) NULL,
    api_key    TEXT NULL,
    params     LONGTEXT NULL,
    created_at DATETIME NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    KEY idx_model_config_created_at (created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS dataset (
    id                  VARCHAR(64)  NOT NULL,
    name                VARCHAR(255) NOT NULL,
    description         TEXT NULL,
    file_name           VARCHAR(512) NULL,
    file_path           TEXT NULL,
    item_count          INT NOT NULL DEFAULT 0,
    has_expected_result TINYINT(1) NOT NULL DEFAULT 0,
    items               LONGTEXT NULL,
    dataset_type        VARCHAR(32) NOT NULL DEFAULT 'text',
    has_multimodal      TINYINT(1) NOT NULL DEFAULT 0,
    modalities          TEXT NULL,
    schema_version      VARCHAR(32) NOT NULL DEFAULT 'v1',
    source_format       VARCHAR(32) NOT NULL DEFAULT 'legacy',
    starred             TINYINT(1) NOT NULL DEFAULT 0,
    has_ppt_expected    TINYINT(1) NOT NULL DEFAULT 0,
    has_html_expected   TINYINT(1) NOT NULL DEFAULT 0,
    created_at          DATETIME NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at          DATETIME NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    KEY idx_dataset_name (name),
    KEY idx_dataset_starred_created_at (starred, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS eval_record (
    id                VARCHAR(64)  NOT NULL,
    name              VARCHAR(255) NOT NULL,
    type              VARCHAR(64) NULL,
    status            VARCHAR(32) NOT NULL DEFAULT 'pending',
    start_time        DATETIME NULL,
    end_time          DATETIME NULL,
    accuracy          DECIMAL(10,4) NULL,
    remark            TEXT NULL,
    judge_mode        VARCHAR(20) NULL,
    judge_model_id    VARCHAR(64) NULL,
    evaluation_type   VARCHAR(32) NOT NULL DEFAULT 'text',
    report_type       VARCHAR(32) NOT NULL DEFAULT 'text',
    has_multimodal    TINYINT(1) NOT NULL DEFAULT 0,
    dataset_type      VARCHAR(32) NULL,
    modalities        TEXT NULL,
    score_status      VARCHAR(32) NULL,
    score_display     VARCHAR(64) NULL,
    sub_eval_id       VARCHAR(64) NULL,
    sub_eval_version  INT NULL,
    targets           LONGTEXT NULL,
    dataset_ids       LONGTEXT NULL,
    dataset_names     LONGTEXT NULL,
    dataset_mapping   LONGTEXT NULL,
    results           LONGTEXT NULL,
    created_at        DATETIME NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at        DATETIME NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    KEY idx_eval_record_created_at (created_at),
    KEY idx_eval_record_status (status),
    KEY idx_eval_record_sub_eval_id (sub_eval_id),
    KEY idx_eval_record_sub_eval_version (sub_eval_id, sub_eval_version)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS criteria_config (
    id                                  TINYINT UNSIGNED NOT NULL,
    similarity_threshold                DECIMAL(6,4) NOT NULL DEFAULT 0.8,
    dimensions                          LONGTEXT NULL,
    text_judge_prompt                   LONGTEXT NULL,
    ppt_judge_prompt                    LONGTEXT NULL,
    html_judge_prompt                   LONGTEXT NULL,
    sub_eval_report_prompt              LONGTEXT NULL,
    overall_report_prompt               LONGTEXT NULL,
    subtype_comparison_prompt           LONGTEXT NULL,
    overall_comparison_prompt           LONGTEXT NULL,
    overall_comparison_prompt_regression LONGTEXT NULL,
    ppt_dimensions                      LONGTEXT NULL,
    html_dimensions                     LONGTEXT NULL,
    semimind_window_size                INT NULL DEFAULT 50,
    PRIMARY KEY (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS model_group (
    id         VARCHAR(64)  NOT NULL,
    name       VARCHAR(255) NOT NULL,
    remark     TEXT NULL,
    created_at DATETIME NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    KEY idx_model_group_created_at (created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS sub_eval_type (
    id         VARCHAR(64)  NOT NULL,
    name       VARCHAR(255) NOT NULL,
    created_at DATETIME NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    KEY idx_sub_eval_type_created_at (created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS sub_evaluation (
    id              VARCHAR(64)  NOT NULL,
    group_id        VARCHAR(64)  NOT NULL,
    name            VARCHAR(255) NOT NULL,
    sub_type        VARCHAR(64)  NOT NULL,
    target_type     VARCHAR(32) NULL,
    targets         LONGTEXT NULL,
    dataset_ids     LONGTEXT NULL,
    dataset_names   LONGTEXT NULL,
    dataset_mapping LONGTEXT NULL,
    judge_mode      VARCHAR(20) NULL,
    judge_model_id  VARCHAR(64) NULL,
    remark          TEXT NULL,
    latest_version  INT NOT NULL DEFAULT 0,
    created_at      DATETIME NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at      DATETIME NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_sub_evaluation_group_type (group_id, sub_type),
    KEY idx_sub_evaluation_group (group_id),
    KEY idx_sub_evaluation_created_at (created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS model_report (
    id               VARCHAR(64)  NOT NULL,
    group_id         VARCHAR(64)  NOT NULL,
    judge_model_id   VARCHAR(64)  NULL,
    status           VARCHAR(32)  NOT NULL DEFAULT 'generating',
    error_message    TEXT NULL,
    selections       LONGTEXT NULL,
    sub_eval_summaries LONGTEXT NULL,
    overall_summary  LONGTEXT NULL,
    prompt_snapshot  LONGTEXT NULL,
    created_at       DATETIME NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at       DATETIME NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    KEY idx_model_report_group_created_at (group_id, created_at),
    KEY idx_model_report_status (status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS model_report_comparison (
    id                 VARCHAR(64) NOT NULL,
    judge_model_id     VARCHAR(64) NULL,
    status             VARCHAR(32) NOT NULL DEFAULT 'generating',
    error_message      TEXT NULL,
    report_ids         LONGTEXT NULL,
    comparison_mode    VARCHAR(20) NULL COMMENT 'performance or regression',
    subtype_comparisons LONGTEXT NULL,
    overall_conclusion LONGTEXT NULL,
    prompt_snapshot    LONGTEXT NULL,
    created_at         DATETIME NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at         DATETIME NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    KEY idx_report_comparison_created_at (created_at),
    KEY idx_report_comparison_status (status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
