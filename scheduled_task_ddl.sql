-- 定时任务管理表
CREATE TABLE IF NOT EXISTS scheduled_task (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '主键',
    name VARCHAR(255) NOT NULL COMMENT '任务名称',
    content TEXT COMMENT '任务内容',
    cron_expression VARCHAR(64) COMMENT 'Cron表达式',
    status VARCHAR(20) DEFAULT 'PAUSED' COMMENT '状态：ACTIVE=运行中, PAUSED=已暂停',
    execute_count INT DEFAULT 0 COMMENT '执行次数',
    last_execute_time DATETIME COMMENT '上次执行时间',
    next_execute_time DATETIME COMMENT '下次执行时间',
    create_time DATETIME COMMENT '创建时间',
    update_time DATETIME COMMENT '更新时间'
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='定时任务表';

CREATE TABLE IF NOT EXISTS task_execution_log (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '主键',
    task_id BIGINT NOT NULL COMMENT '关联任务ID',
    task_name VARCHAR(255) COMMENT '任务名称快照',
    content TEXT COMMENT '任务内容快照',
    status VARCHAR(20) COMMENT '执行状态：SUCCESS/FAILED',
    result TEXT COMMENT '执行结果',
    execute_time DATETIME COMMENT '执行时间'
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='任务执行日志表';