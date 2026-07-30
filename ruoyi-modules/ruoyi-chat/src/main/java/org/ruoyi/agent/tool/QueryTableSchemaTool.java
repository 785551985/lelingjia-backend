package org.ruoyi.agent.tool;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;

import javax.sql.DataSource;

import org.ruoyi.common.core.utils.SpringUtils;
import org.springframework.stereotype.Component;

import com.baomidou.dynamic.datasource.toolkit.DynamicDataSourceContextHolder;

import dev.langchain4j.agent.tool.Tool;
import lombok.extern.slf4j.Slf4j;
import org.ruoyi.mcp.service.core.BuiltinToolProvider;

@Component
@Slf4j
public class QueryTableSchemaTool implements BuiltinToolProvider {

    // 使用延迟初始化，避免在构造函数中调用 SpringUtils.getBean()
    private DataSource getDataSource() {
        return SpringUtils.getBean(DataSource.class);
    }

    @Tool("查询指定数据库表的详细字段结构、数据类型、主键与索引信息")
    public String queryTableSchema(String tableName) {
        // 2. 手动推入数据源上下文
        DynamicDataSourceContextHolder.push("agent");
        if (tableName == null || tableName.trim().isEmpty()) {
            return "Error: Table name cannot be empty";
        }

        if (!tableName.matches("^[a-zA-Z0-9_]+$")) {
            return "Error: Invalid table name format";
        }

        String sql = "SELECT column_name, data_type, character_maximum_length, is_nullable, column_default " +
                     "FROM information_schema.columns WHERE table_name = ? ORDER BY ordinal_position";

        try (Connection connection = getDataSource().getConnection();
                PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, tableName.toLowerCase());
            try (ResultSet rs = ps.executeQuery()) {
                StringBuilder schema = new StringBuilder("Table: " + tableName + "\nColumns:\n");
                boolean found = false;
                while (rs.next()) {
                    found = true;
                    schema.append(" - ").append(rs.getString("column_name"))
                          .append(" (").append(rs.getString("data_type")).append(")");
                    String nullable = rs.getString("is_nullable");
                    if ("NO".equalsIgnoreCase(nullable)) {
                        schema.append(" NOT NULL");
                    }
                    schema.append("\n");
                }
                return found ? schema.toString() : "Table not found: " + tableName;
            }

        } catch (Exception e) {
               // 3. 必须在 finally 中清除上下文，防止污染其他请求
            DynamicDataSourceContextHolder.clear();
            log.error("Error querying table schema: {}", tableName, e);
            return "Error: " + e.getMessage();
        } finally {
            // 3. 必须在 finally 中清除上下文，防止污染其他请求
            DynamicDataSourceContextHolder.clear();
        }
    }

    @Override
    public String getToolName() {
        return "query_table_schema";
    }

    @Override
    public String getDisplayName() {
        return "查询表结构";
    }

    @Override
    public String getDescription() {
        return "查询指定数据库表的详细字段结构、数据类型、主键与索引信息";
    }
}
