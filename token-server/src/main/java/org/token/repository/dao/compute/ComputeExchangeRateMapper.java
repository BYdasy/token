package org.token.repository.dao.compute;

import java.util.*;
import java.time.*;
import java.math.*;
import java.util.Map;
import org.apache.ibatis.annotations.*;

/** ComputeExchangeRateService 的参数化数据库语句。 */
@Mapper
public interface ComputeExchangeRateMapper {

    @Select("SELECT provider FROM compute_exchange_refresh WHERE provider=#{args[0]} AND last_attempt_at=#{args[1]} AND last_status='RUNNING' FOR UPDATE")
    List<Map<String, Object>> refreshProviderExchangeRefreshRows(@Param("args") Object[] args);

    @Update("UPDATE compute_exchange_refresh SET last_status='CONFIG_CHANGED',next_attempt_at=#{args[0]} WHERE provider=#{args[1]}")
    int refreshProviderExchangeRefreshWrite(@Param("args") Object[] args);

    @Update("UPDATE compute_exchange_refresh SET last_status=#{args[0]},last_success_at=CASE WHEN #{args[1]} THEN #{args[2]} ELSE last_success_at END WHERE provider=#{args[3]}")
    int refreshProviderExchangeRefreshWrite2(@Param("args") Object[] args);

    @Update("UPDATE compute_exchange_refresh SET last_status=#{args[0]} WHERE provider=#{args[1]} AND last_attempt_at=#{args[2]} AND last_status='RUNNING'")
    int finishExchangeRefreshWrite(@Param("args") Object[] args);

    @Select("SELECT DISTINCT a.asset_code FROM compute_currency_asset a JOIN compute_currency c ON c.currency=a.asset_code JOIN compute_currency_network n ON n.network_code=c.network WHERE a.enabled=1 AND a.asset_type='CRYPTO' AND c.enabled=1 AND n.enabled=1")
    List<Map<String, Object>> enabledCryptoCurrencyAssetRows(@Param("args") Object[] args);

    @Insert("INSERT INTO compute_exchange_rate(rate_type,currency,rate,provider,source_id,source_at,refreshed_at) VALUES(#{args[0]},#{args[1]},#{args[2]},#{args[3]},#{args[4]},#{args[5]},#{args[6]}) ON DUPLICATE KEY UPDATE rate=CASE WHEN VALUES(source_at)>=source_at THEN VALUES(rate) ELSE rate END,provider=CASE WHEN VALUES(source_at)>=source_at THEN VALUES(provider) ELSE provider END,source_id=CASE WHEN VALUES(source_at)>=source_at THEN VALUES(source_id) ELSE source_id END,refreshed_at=CASE WHEN VALUES(source_at)>=source_at THEN VALUES(refreshed_at) ELSE refreshed_at END,source_at=GREATEST(source_at,VALUES(source_at))")
    int persistExchangeRateWrite(@Param("args") Object[] args);

    @Select("SELECT rate_type,currency,rate,provider,source_id,source_at,refreshed_at FROM compute_exchange_rate ORDER BY rate_type,currency")
    List<Map<String, Object>> valuationExchangeRateRows(@Param("args") Object[] args);

    @Select("SELECT asset_code FROM compute_currency_asset WHERE asset_type='FIAT'")
    List<Map<String, Object>> valuationCurrencyAssetRows(@Param("args") Object[] args);

    @Select("SELECT provider,last_attempt_at,last_success_at,next_attempt_at,last_status FROM compute_exchange_refresh WHERE provider IN ('FRANKFURTER','CRYPTO') ORDER BY provider")
    List<Map<String, Object>> statusExchangeRefreshRows(@Param("args") Object[] args);

    @Update("""
        <script>UPDATE compute_exchange_refresh SET next_attempt_at=#{nextAttempt},last_attempt_at=#{attempt},last_status='RUNNING'
        WHERE provider=#{provider}
        <choose><when test='force'>
          AND ((last_status&lt;&gt;'RUNNING' AND (last_attempt_at IS NULL OR last_attempt_at&lt;=#{forceBefore}))
            OR next_attempt_at&lt;=#{attempt}
            OR (last_status='RUNNING' AND last_attempt_at&lt;=#{expiredLeaseBefore}))
        </when><otherwise>
          AND (next_attempt_at IS NULL OR next_attempt_at&lt;=#{attempt})
          <if test='provider == "FRANKFURTER"'>AND (last_status='CONFIG_CHANGED' OR last_attempt_at IS NULL OR last_attempt_at&lt;=#{automaticBefore})</if>
        </otherwise></choose></script>
        """)
    int claimProvider(@Param("provider") String provider, @Param("force") boolean force, @Param("attempt") LocalDateTime attempt,
            @Param("nextAttempt") LocalDateTime nextAttempt, @Param("forceBefore") LocalDateTime forceBefore,
            @Param("automaticBefore") LocalDateTime automaticBefore,@Param("expiredLeaseBefore") LocalDateTime expiredLeaseBefore);
}
