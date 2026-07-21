-- 补单系统 - 创建数据库表
-- 在 MySQL 中执行：source 此文件 或 复制粘贴执行

CREATE TABLE IF NOT EXISTS order_supplement (
    id          BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '主键',
    shop_id     VARCHAR(200) NOT NULL COMMENT '店铺标识（平台/店铺名/shopId）',
    product_name     VARCHAR(255) NOT NULL COMMENT '商品名称',
    product_id  VARCHAR(100) NOT NULL COMMENT '商品ID',
    sku_id      VARCHAR(100) DEFAULT NULL COMMENT '商品SKU ID',
    price       DECIMAL(10,2) DEFAULT 0.00 COMMENT '价格',
    quantity    INT          DEFAULT 1 COMMENT '拍单数量',
    remark      VARCHAR(500) DEFAULT NULL COMMENT '价格备注',
    review_image    TEXT     DEFAULT NULL COMMENT '评论图片，逗号分隔的相对路径',
    review_text     TEXT     DEFAULT NULL COMMENT '评论文本',
    status      VARCHAR(20)  NOT NULL DEFAULT '待补单' COMMENT '补单状态：待补单/已补单/取消补单',
    group_id    BIGINT       DEFAULT NULL COMMENT '所属组ID',
    create_time DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    update_time DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    INDEX idx_shop_id (shop_id),
    INDEX idx_product_id (product_id),
    INDEX idx_status (status),
    INDEX idx_group_id (group_id),
    FULLTEXT INDEX ft_product (product_name, product_id, sku_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='补单记录表';

