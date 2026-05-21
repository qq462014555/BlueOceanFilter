-- 淘宝店铺信息表
CREATE TABLE IF NOT EXISTS taobao_shop (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    wangwang_name VARCHAR(100) COMMENT '淘宝旺旺名',
    shop_name VARCHAR(200) COMMENT '店铺名称',
    shop_version VARCHAR(50) COMMENT '版本（基础版/标准版等）',
    group_name VARCHAR(100) COMMENT '店铺分组',
    tags VARCHAR(500) COMMENT '标签，逗号分隔',
    account_balance DOUBLE COMMENT '推广账户余额',
    create_time DATETIME,
    update_time DATETIME
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='淘宝店铺表';

-- 淘宝推广报表数据
CREATE TABLE IF NOT EXISTS taobao_ad_report (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    shop_id BIGINT COMMENT '关联店铺ID',
    wangwang_name VARCHAR(100) COMMENT '淘宝旺旺名',
    report_date DATE COMMENT '数据日期',
    impressions BIGINT COMMENT '展现量',
    clicks BIGINT COMMENT '点击量',
    cost DOUBLE COMMENT '花费',
    click_rate DOUBLE COMMENT '点击率',
    total_gmv DOUBLE COMMENT '总成交金额',
    roi DOUBLE COMMENT '投入产出比',
    keyword_cost DOUBLE COMMENT '关键词花费',
    keyword_gmv DOUBLE COMMENT '关键词成交金额',
    keyword_roi DOUBLE COMMENT '关键词投产比',
    site_cost DOUBLE COMMENT '全站花费',
    site_gmv DOUBLE COMMENT '全站成交金额',
    site_roi DOUBLE COMMENT '全站投产比',
    crowd_cost DOUBLE COMMENT '人群花费',
    crowd_gmv DOUBLE COMMENT '人群成交金额',
    crowd_roi DOUBLE COMMENT '人群投产比',
    create_time DATETIME
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='淘宝推广报表';
