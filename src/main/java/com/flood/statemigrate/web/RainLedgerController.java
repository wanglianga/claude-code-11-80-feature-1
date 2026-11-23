package com.flood.statemigrate.web;

import com.flood.statemigrate.domain.RainChangeKind;
import com.flood.statemigrate.domain.RainLedgerEntry;
import com.flood.statemigrate.service.RainLedgerService;
import com.flood.statemigrate.web.dto.LedgerChangeReq;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@Tag(name = "降雨事件版本账")
@RestController
@RequestMapping("/api/rain/ledger")
public class RainLedgerController {

    private final RainLedgerService ledgerService;

    public RainLedgerController(RainLedgerService ledgerService) {
        this.ledgerService = ledgerService;
    }

    @Operation(summary = "登记变更（站点增补/设备更换/权重变动）：应用变更并按原参数重算两方案，"
            + "落账对已计算风险窗口的影响")
    @PostMapping("/changes")
    @ResponseStatus(HttpStatus.CREATED)
    public RainLedgerEntry change(@Valid @RequestBody LedgerChangeReq req) {
        return ledgerService.recordChange(new RainLedgerService.ChangeReq(
                req.changeType(), req.effectiveAt(), req.reprocessFrom(), req.reprocessTo(),
                req.stationCode(), req.stationName(), req.newClockOffsetSec(), req.newEquipmentId(),
                req.weights(), req.areas(), req.note()));
    }

    @Operation(summary = "版本账查询（可按变更类型过滤）")
    @GetMapping
    public List<RainLedgerEntry> list(@RequestParam(required = false) RainChangeKind changeType) {
        return ledgerService.list(changeType);
    }
}
