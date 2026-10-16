package org.token.controller.admin;

import lombok.RequiredArgsConstructor;
import org.token.custom.LogAction;
import org.token.model.response.Result;
import org.token.tools.response.ResultFactory;
import org.token.service.compute.ComputeDepositAddressManagementService;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/** 充值地址与用户自行绑定的提款地址独立，接口授权不能由代理行范围替代。 */
@org.token.custom.web.ComputeApi
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/compute/admin/deposit-addresses")
public class ComputeDepositAddressController {
    private final ComputeDepositAddressManagementService addresses;
    private final ResultFactory results;

    @GetMapping
    @PreAuthorize("hasAnyAuthority('20400','super')")
    public Result page(@RequestParam(defaultValue="1") int page,@RequestParam(defaultValue="20") int size,
                       @RequestParam(required=false) String username,@RequestParam(required=false) String network,@RequestParam(required=false) String status,
                       @RequestParam(required=false) String address,@RequestParam(required=false) String currency) {
        return results.success(addresses.page(page,size,username,network,status,address,currency));
    }

    @GetMapping("/filter-options")
    @PreAuthorize("hasAnyAuthority('20400','super')")
    public Result filterOptions() {
        return results.success(addresses.filterOptions());
    }

    @GetMapping("/networks")
    @PreAuthorize("hasAnyAuthority('20400','20401','super')")
    public Result networks(@RequestParam(defaultValue="COMPUTE") String wallet) { return results.success(addresses.networks(wallet)); }

    @PostMapping
    @PreAuthorize("hasAnyAuthority('20401','super')")
    @LogAction(type="后台生成用户充值地址")
    public Result generate(@RequestBody Map<String,Object> body) {
        return results.success(addresses.generate(java.util.Objects.toString(body.get("username"), "").trim(),
                java.util.Objects.toString(body.get("network"), "").trim(), java.util.Objects.toString(body.get("walletType"), "").trim()));
    }

    @PostMapping("/{id}/disable")
    @PreAuthorize("hasAnyAuthority('20402','super')")
    @LogAction(type="后台停用用户充值地址")
    public Result disable(@PathVariable long id) { addresses.disable(id);return results.success(); }
}
