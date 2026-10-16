package org.token.repository.dao.compute;

import java.util.List;
import java.util.Map;
import org.apache.ibatis.annotations.*;

/** ComputeAutoService 的参数化数据库语句。 */
@Mapper
public interface ComputeAutoMapper {

    @Select("SELECT * FROM compute_auto_contract WHERE member_id=#{args[0]} ORDER BY id DESC LIMIT #{args[1]} OFFSET #{args[2]}")
    List<Map<String, Object>> recordsAutoContractRows(@Param("args") Object[] args);

    @Select("SELECT COUNT(*) AS repository_value FROM compute_auto_contract WHERE member_id=#{args[0]}")
    List<Map<String, Object>> recordsAutoContractCount(@Param("args") Object[] args);

    @Select("SELECT id,level,vip_expires_at,auto_next_at,token_income_percent FROM compute_member WHERE id=#{args[0]} AND enabled=1 FOR UPDATE")
    List<Map<String, Object>> activateMemberRow(@Param("args") Object[] args);

    @Select("SELECT * FROM compute_auto_contract WHERE member_id=#{args[0]} AND request_id=#{args[1]}")
    List<Map<String, Object>> activateAutoContractRows(@Param("args") Object[] args);

    @Select("SELECT * FROM compute_level WHERE id=#{args[0]} AND enabled=1 FOR UPDATE")
    List<Map<String, Object>> activateLevelRow(@Param("args") Object[] args);

    @Select("SELECT available FROM compute_wallet WHERE member_id=#{args[0]} AND wallet_type='COMPUTE' AND currency=#{args[1]} FOR UPDATE")
    List<Map<String, Object>> activateWalletRows(@Param("args") Object[] args);

    @Update("UPDATE compute_member SET auto_next_at=#{args[0]} WHERE id=#{args[1]}")
    int activateMemberWrite(@Param("args") Object[] args);

    @Select("SELECT * FROM compute_auto_contract WHERE member_id=#{args[0]} AND order_no=#{args[1]}")
    List<Map<String, Object>> recordAutoContractRow(@Param("args") Object[] args);

    @Select("SELECT id,level,vip_expires_at,auto_next_at FROM compute_member WHERE id=#{args[0]} AND enabled=1")
    List<Map<String, Object>> statusMemberRow(@Param("args") Object[] args);

    @Select("SELECT * FROM compute_level WHERE id=#{args[0]}")
    List<Map<String, Object>> statusLevelRow(@Param("args") Object[] args);

    @Select("SELECT COUNT(*) AS repository_value FROM compute_auto_contract WHERE member_id=#{args[0]} AND created_at>=#{args[1]} AND created_at<#{args[2]}")
    List<Map<String, Object>> statusOfAutoContractCount(@Param("args") Object[] args);

    @Select("SELECT available FROM compute_wallet WHERE member_id=#{args[0]} AND wallet_type='COMPUTE' AND currency=#{args[1]}")
    List<Map<String, Object>> statusOfWalletRows(@Param("args") Object[] args);


    @Select("SELECT * FROM compute_auto_contract WHERE member_id=#{args[0]} AND details_status='PENDING' ORDER BY id DESC LIMIT 1")
    List<Map<String, Object>> statusOfAutoContractRows2(@Param("args") Object[] args);


    @Select("SELECT id FROM compute_auto_contract WHERE details_status='PENDING' AND result_available_at<=#{args[0]} ORDER BY result_available_at LIMIT 100")
    List<Map<String, Object>> finishDueAutoContractRows2(@Param("args") Object[] args);

    @Select("SELECT * FROM compute_auto_contract WHERE id=#{args[0]} FOR UPDATE")
    List<Map<String, Object>> finishDetailsAutoContractRow(@Param("args") Object[] args);

    @Update("UPDATE compute_auto_contract SET execution_result=#{args[0]},details_status='READY' WHERE id=#{args[1]}")
    int finishDetailsAutoContractWrite(@Param("args") Object[] args);





}
