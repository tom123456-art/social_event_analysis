# 共享资源

- `rules`：Java 与 Python 共同读取的文本质量规则；Maven 将其打入 ETL JAR 根目录。
- `fixtures`：共同测试样本；Maven 将其放入 ETL 测试资源根目录。
- `models/Erlangshen-Roberta-330M-Sentiment`：原本位于 `server/etl/models` 的本地模型，完整迁移；大体积权重继续不提交 Git。

不要在这里放 Java 或 Python 业务源码，也不要将模型目录标记为 IDEA Sources Root。
