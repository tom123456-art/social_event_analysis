package com.social.hotspot.integration.hive;
import com.social.hotspot.integration.hive.HiveWarehouseSchema;
import com.jcraft.jsch.Session;
import com.social.hotspot.integration.spark.RemoteCommandResult;
import static com.social.hotspot.integration.spark.RemoteCommandExecutor.*;

public final class HivePartitionRegistrar {
    /** 工具类私有构造，禁止实例化。 */
    private HivePartitionRegistrar() {}
    /** 在远程虚拟机上执行 Hive SQL，为指定批次注册 ODS、DWD 分区并应用 DWS 建表脚本，同步补齐缺失字段。 */
    public static void register(Session session, String batchId, String rawPath, String dwdPath, String appHome, String warehouseOutput, int timeoutSeconds) throws Exception {
        // 将批次号中的单引号转义为 SQL 字符串格式；构造注册 ODS、DWD 和 DWS 批次分区的 Hive SQL。
        String partition = batchId.replace("'", "''");
        String sql = "ALTER TABLE social_ods.ods_social_content_raw ADD IF NOT EXISTS PARTITION (batch_id='" + partition
                // 继续拼接命令、路径或文本：+ "') LOCATION '" + rawPath.replace("'", "''") + "'; "；sh 负责参数引号转义。
                + "') LOCATION '" + rawPath.replace("'", "''") + "'; "
                // 继续拼接命令、路径或文本：+ "ALTER TABLE social_dwd.dwd_social_content_detail ADD IF NOT EXISTS PARTITION (batch_id='" + partition；sh 负责参数引号转义。
                + "ALTER TABLE social_dwd.dwd_social_content_detail ADD IF NOT EXISTS PARTITION (batch_id='" + partition
                // 继续拼接命令、路径或文本：+ "') LOCATION '" + dwdPath.replace("'", "''") + "'";；sh 负责参数引号转义。
                + "') LOCATION '" + dwdPath.replace("'", "''") + "'";
        // 构造执行 Hive 分区注册的远程命令。
        RemoteCommandResult hiveSchema = exec(session, "cat " + sh(appHome + "/deploy/sql/hive_tables.sql"), timeoutSeconds);
        if (hiveSchema.stdout().isBlank()) throw new IllegalStateException("Missing Hive warehouse schema");
        exec(session, hiveCli() + " -e " + sh(hiveSchema.stdout()), timeoutSeconds);
        for (String table : new String[]{"social_ods.ods_social_content_raw", "social_dwd.dwd_social_content_detail"}) {
            RemoteCommandResult description = exec(session, hiveCli() + " -e " + sh("DESCRIBE " + table), timeoutSeconds);
            java.util.Set<String> columns = new java.util.HashSet<>();
            for (String line : description.stdout().split("\\R")) {
                String[] cells = line.split("\\t", -1);
                if (cells.length >= 2) columns.add(cells[0].trim());
            }
            if (!columns.contains("event_id")) throw new IllegalStateException("Cannot read Hive schema: " + table);
            String upgrade = HiveWarehouseSchema.missingColumns(table, columns);
            if (!upgrade.isEmpty()) exec(session, hiveCli() + " -e " + sh(upgrade), timeoutSeconds);
        }
        exec(session, hiveCli() + " -e " + sh("ALTER TABLE social_ods.ods_social_content_raw SET TBLPROPERTIES ('skip.header.line.count'='1'); " + sql), timeoutSeconds);
        RemoteCommandResult manifest = exec(session, "hdfs dfs -cat "
                + sh(warehouseOutput + "/manifests/batch_id=" + batchId + "/dws.sql"), timeoutSeconds);
        if (manifest.stdout().isBlank()) throw new IllegalStateException("Missing DWS Hive manifest");
        exec(session, hiveCli() + " -e " + sh(manifest.stdout()), timeoutSeconds);
    }
    /** 构造 beeline 命令行：预加载兼容库并设置 Hive 连接参数，返回完整命令字符串。 */
    private static String hiveCli() {
        // 旧版虚拟机的系统库通过工具库导出伪终端符号，需预先加载以供终端组件使用。
        return "if [ -r /lib64/libutil.so.1 ]; then export LD_PRELOAD=/lib64/libutil.so.1; fi; "
                + "env HADOOP_HOME=/opt/bigdata/hadoop-client HADOOP_CONF_DIR=/opt/bigdata/hadoop-client/etc/hadoop "
                + "HADOOP_OPTS='-Djline.terminal=jline.UnsupportedTerminal -Djansi.passthrough=true -Djansi.force=false' "
                + "TERM=dumb /opt/bigdata/hive/bin/beeline --color=false --silent=true --showHeader=false --outputformat=tsv2 "
                + "-u 'jdbc:hive2://127.0.0.1:10000/default' -n root";
    }
}
