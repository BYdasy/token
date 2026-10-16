package org.token.service.compute;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;

/** 远端地址归属先查、本地资金后锁；事务由不同 Bean 的最终业务入口建立。 */
@Service
@RequiredArgsConstructor
@Transactional(propagation = Propagation.NEVER)
public class ComputeWithdrawalCoordinator {
    private final ComputeBusinessService business;
    private final ComputeManagementService management;
    private final ComputeWithdrawalAddressGuard guard;

    public Map<String,Object> withdraw(long memberId,Map<String,Object> request) {
        ComputeWithdrawalAddressGuard.assertOutsideTransaction();
        var previous=business.withdrawalReplay(memberId,request);
        if(previous!=null)return previous;
        var proof=guard.verifyRemote(business.withdrawalCheck(memberId,request));
        return business.withdraw(memberId,request,proof);
    }

    public Map<String,Object> saveAddress(long memberId,Map<String,Object> request) {
        ComputeWithdrawalAddressGuard.assertOutsideTransaction();
        var proof=guard.verifyRemote(business.addressCheck(memberId,request));
        return business.saveAddress(memberId,request,proof);
    }

    public Map<String,Object> manualWithdrawal(Map<String,Object> request) {
        ComputeWithdrawalAddressGuard.assertOutsideTransaction();
        var prepared=management.prepareManualWithdrawal(request);
        if(prepared.replay()!=null)return prepared.replay();
        var proof=guard.verifyRemote(prepared.check());
        return management.manualWithdrawal(request,proof);
    }

    public Map<String,Object> saveManagedAddress(Long id,Map<String,Object> request) {
        ComputeWithdrawalAddressGuard.assertOutsideTransaction();
        var check=management.prepareManagedAddress(id,request);
        var proof=check==null?null:guard.verifyRemote(check);
        return management.saveAddress(id,request,proof);
    }
}