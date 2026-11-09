package com.examprep.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.examprep.entity.AiAgentMemory;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

/**
 * Agent 长期记忆 Mapper
 *
 * <p><b>为什么手写 SQL 而不是用 {@code @TableLogic}</b>：本表在
 * {@code UNIQUE(user_id, mem_key)} 上要做「删除后复活」，如果让 MyBatis-Plus
 * 自动加 {@code is_deleted=0} 条件，会与「复活需要读到已删除行」的逻辑互相打架，
 * 且 {@code selectById} 的行为会随注解隐式变化——这类隐式语义正是排查时最花时间的。
 * 这里全部显式写清楚：读操作一律显式带 {@code is_deleted = 0}，复活走 {@link #revive}。
 */
public interface AiAgentMemoryMapper extends BaseMapper<AiAgentMemory> {

    /**
     * 某学生的有效记忆（按更新时间倒序）。
     *
     * @param userId 学生 ID
     * @param limit  返回条数上限
     * @return 记忆列表（无则空列表）
     */
    @Select("SELECT * FROM ai_agent_memory WHERE user_id = #{userId} AND is_deleted = 0 "
            + "ORDER BY updated_at DESC, id DESC LIMIT #{limit}")
    List<AiAgentMemory> selectActive(@Param("userId") Long userId, @Param("limit") int limit);

    /**
     * 按记忆键取有效记忆（用于判重）。
     *
     * @param userId 学生 ID
     * @param memKey 记忆键
     * @return 记忆或 null
     */
    @Select("SELECT * FROM ai_agent_memory WHERE user_id = #{userId} AND mem_key = #{memKey} "
            + "LIMIT 1")
    AiAgentMemory selectAnyByKey(@Param("userId") Long userId, @Param("memKey") String memKey);

    /**
     * 按 ID 取有效记忆（不校验归属，归属由服务层判断 → 越权返回 403 而不是 404）。
     *
     * @param id 记忆 ID
     * @return 记忆或 null
     */
    @Select("SELECT * FROM ai_agent_memory WHERE id = #{id}")
    AiAgentMemory selectAnyById(@Param("id") Long id);

    /**
     * 某学生的有效记忆条数（上限校验用）。
     *
     * @param userId 学生 ID
     * @return 条数
     */
    @Select("SELECT COUNT(*) FROM ai_agent_memory WHERE user_id = #{userId} AND is_deleted = 0")
    long countActive(@Param("userId") Long userId);

    /**
     * 复活已逻辑删除的记忆（覆盖 value/source/status，清 deleted）。
     *
     * <p>为什么必须单独一条 SQL：{@code UNIQUE(user_id, mem_key)} 让 INSERT 必撞键，
     * 学生「删了再加同一个键」是再正常不过的操作，不能因此 500。
     *
     * @return 受影响行数（0 表示该行已被并发改掉）
     */
    @Update("UPDATE ai_agent_memory SET mem_value = #{memValue}, source = #{source}, "
            + "status = 'ACTIVE', is_deleted = 0, updated_at = NOW() WHERE id = #{id}")
    int revive(@Param("id") Long id,
               @Param("memValue") String memValue,
               @Param("source") String source);

    /**
     * 更新记忆值（学生编辑）。
     *
     * @return 受影响行数
     */
    @Update("UPDATE ai_agent_memory SET mem_value = #{memValue}, updated_at = NOW() "
            + "WHERE id = #{id} AND is_deleted = 0")
    int updateValue(@Param("id") Long id, @Param("memValue") String memValue);

    /**
     * 逻辑删除（学生删除）。
     *
     * @return 受影响行数
     */
    @Update("UPDATE ai_agent_memory SET is_deleted = 1, updated_at = NOW() "
            + "WHERE id = #{id} AND is_deleted = 0")
    int softDelete(@Param("id") Long id);

    /**
     * 显式插入（时间戳由 DB 生成，避免应用与 DB 时钟不一致）。
     *
     * @return 受影响行数
     */
    @Insert("INSERT INTO ai_agent_memory (user_id, mem_key, mem_value, source, status, is_deleted) "
            + "VALUES (#{userId}, #{memKey}, #{memValue}, #{source}, 'ACTIVE', 0)")
    int insertMemory(@Param("userId") Long userId,
                     @Param("memKey") String memKey,
                     @Param("memValue") String memValue,
                     @Param("source") String source);

    /**
     * 管理端：按学生读取有效记忆（只读）。
     *
     * @param userId 学生 ID
     * @param limit  上限
     * @return 记忆列表
     */
    @Select("SELECT * FROM ai_agent_memory WHERE user_id = #{userId} AND is_deleted = 0 "
            + "ORDER BY updated_at DESC, id DESC LIMIT #{limit}")
    List<AiAgentMemory> selectActiveForAdmin(@Param("userId") Long userId,
                                             @Param("limit") int limit);
}
