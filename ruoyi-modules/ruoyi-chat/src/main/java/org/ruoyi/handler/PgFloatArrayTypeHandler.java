package org.ruoyi.handler;

import org.apache.ibatis.type.BaseTypeHandler;
import org.apache.ibatis.type.JdbcType;
import org.apache.ibatis.type.MappedJdbcTypes;
import org.apache.ibatis.type.MappedTypes;

import java.sql.*;

/**
 * PostgreSQL real[] / float4[] 原生数组 MyBatis 类型处理器
 */
@MappedJdbcTypes(JdbcType.ARRAY)
@MappedTypes(Float[].class)
public class PgFloatArrayTypeHandler extends BaseTypeHandler<Float[]> {

    @Override
    public void setNonNullParameter(PreparedStatement ps, int i, Float[] parameter, JdbcType jdbcType) throws SQLException {
        if (parameter == null) {
            ps.setNull(i, Types.ARRAY);
        } else {
            Array array = ps.getConnection().createArrayOf("float4", parameter);
            ps.setArray(i, array);
        }
    }

    @Override
    public Float[] getNullableResult(ResultSet rs, String columnName) throws SQLException {
        return getFloatArray(rs.getArray(columnName));
    }

    @Override
    public Float[] getNullableResult(ResultSet rs, int columnIndex) throws SQLException {
        return getFloatArray(rs.getArray(columnIndex));
    }

    @Override
    public Float[] getNullableResult(CallableStatement cs, int columnIndex) throws SQLException {
        return getFloatArray(cs.getArray(columnIndex));
    }

    private Float[] getFloatArray(Array array) throws SQLException {
        if (array == null) {
            return null;
        }
        Object obj = array.getArray();
        if (obj instanceof float[]) {
            float[] primitive = (float[]) obj;
            Float[] res = new Float[primitive.length];
            for (int i = 0; i < primitive.length; i++) {
                res[i] = primitive[i];
            }
            return res;
        } else if (obj instanceof Float[]) {
            return (Float[]) obj;
        } else if (obj instanceof Number[]) {
            Number[] nums = (Number[]) obj;
            Float[] res = new Float[nums.length];
            for (int i = 0; i < nums.length; i++) {
                res[i] = nums[i] != null ? nums[i].floatValue() : 0f;
            }
            return res;
        }
        return null;
    }
}
