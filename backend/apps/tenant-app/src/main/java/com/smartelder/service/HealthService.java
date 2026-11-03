package com.smartelder.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.smartelder.common.BizException;
import com.smartelder.common.VitalType;
import com.smartelder.dto.HealthDtos;
import com.smartelder.entity.HealthArchive;
import com.smartelder.entity.MedicationPlan;
import com.smartelder.entity.VitalRecord;
import com.smartelder.mapper.HealthArchiveMapper;
import com.smartelder.mapper.MedicationPlanMapper;
import com.smartelder.mapper.VitalRecordMapper;
import com.smartelder.security.LoginUser;
import java.time.LocalDateTime;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class HealthService {

    private final HealthArchiveMapper archiveMapper;
    private final VitalRecordMapper vitalMapper;
    private final MedicationPlanMapper medicationMapper;
    private final ElderAccessService accessService;
    private final AlertService alertService;

    public HealthService(HealthArchiveMapper archiveMapper, VitalRecordMapper vitalMapper,
                         MedicationPlanMapper medicationMapper, ElderAccessService accessService,
                         AlertService alertService) {
        this.archiveMapper = archiveMapper;
        this.vitalMapper = vitalMapper;
        this.medicationMapper = medicationMapper;
        this.accessService = accessService;
        this.alertService = alertService;
    }

    public HealthArchive getArchive(Long elderId, LoginUser user) {
        accessService.requireReadable(elderId, user);
        HealthArchive archive = archiveMapper.selectOne(
                Wrappers.<HealthArchive>lambdaQuery().eq(HealthArchive::getElderId, elderId));
        if (archive == null) {
            archive = new HealthArchive();
            archive.setElderId(elderId);
            archive.setUpdatedAt(LocalDateTime.now());
            archiveMapper.insert(archive);
        }
        return archive;
    }

    @Transactional
    public HealthArchive saveArchive(Long elderId, HealthDtos.ArchiveRequest req, LoginUser user) {
        accessService.requireReadable(elderId, user);
        HealthArchive archive = getArchive(elderId, user);
        archive.setHeightCm(req.heightCm());
        archive.setWeightKg(req.weightKg());
        archive.setBloodType(req.bloodType());
        archive.setMedicalHistory(req.medicalHistory());
        archive.setAllergyHistory(req.allergyHistory());
        archive.setMedicationHistory(req.medicationHistory());
        archive.setSystolicRange(req.systolicRange());
        archive.setDiastolicRange(req.diastolicRange());
        archive.setUpdatedAt(LocalDateTime.now());
        archiveMapper.updateById(archive);
        return archive;
    }

    /**
     * 体征上报（HC-06）。写入后按阈值判断是否异常（HC-09），异常则触发黄色告警。
     * 老人默认写自己，家属与服务方需显式指定 elderId 且通过授权校验。
     */
    @Transactional
    public HealthDtos.VitalView reportVital(Long elderId, HealthDtos.VitalRequest req, LoginUser user,
                                            String source) {
        accessService.requireReadable(elderId, user);
        VitalType type = VitalType.parse(req.type());
        VitalRecord record = new VitalRecord();
        record.setElderId(elderId);
        record.setType(type.name());
        record.setValue1(req.value1());
        record.setValue2(req.value2());
        record.setUnit(type.unit());
        boolean abnormal = type.isAbnormal(req.value1(), req.value2());
        record.setAbnormal(abnormal ? 1 : 0);
        record.setSource(source);
        record.setMeasuredAt(req.measuredAt() == null ? LocalDateTime.now() : req.measuredAt());
        record.setCreatedAt(LocalDateTime.now());
        vitalMapper.insert(record);

        if (abnormal) {
            alertService.trigger(elderId, "VITAL_ABNORMAL",
                    type.label() + "异常：" + formatValue(record) + type.unit(), null, null, user.userId());
        }
        return toView(record);
    }

    private String formatValue(VitalRecord record) {
        if (record.getValue2() != null) {
            return record.getValue1().stripTrailingZeros().toPlainString()
                    + "/" + record.getValue2().stripTrailingZeros().toPlainString();
        }
        return record.getValue1().stripTrailingZeros().toPlainString();
    }

    public List<HealthDtos.VitalView> listVitals(Long elderId, String type, int limit, LoginUser user) {
        accessService.requireReadable(elderId, user);
        var query = Wrappers.<VitalRecord>lambdaQuery().eq(VitalRecord::getElderId, elderId);
        if (type != null && !type.isBlank()) {
            query.eq(VitalRecord::getType, type);
        }
        query.orderByDesc(VitalRecord::getMeasuredAt).last("LIMIT " + limit);
        return vitalMapper.selectList(query).stream().map(this::toView).toList();
    }

    /** 首页/家属面板用的最新体征，每种类型取一条。 */
    public List<HealthDtos.VitalView> latestVitals(Long elderId) {
        return List.of(VitalType.values()).stream()
                .map(t -> vitalMapper.selectOne(Wrappers.<VitalRecord>lambdaQuery()
                        .eq(VitalRecord::getElderId, elderId)
                        .eq(VitalRecord::getType, t.name())
                        .orderByDesc(VitalRecord::getMeasuredAt)
                        .last("LIMIT 1")))
                .filter(java.util.Objects::nonNull)
                .map(this::toView)
                .toList();
    }

    private HealthDtos.VitalView toView(VitalRecord r) {
        return new HealthDtos.VitalView(r.getId(), r.getType(), r.getValue1(), r.getValue2(), r.getUnit(),
                r.getAbnormal() != null && r.getAbnormal() == 1, r.getSource(), r.getMeasuredAt());
    }

    public List<HealthDtos.MedicationView> listMedications(Long elderId, LoginUser user) {
        accessService.requireReadable(elderId, user);
        return medicationMapper.selectList(Wrappers.<MedicationPlan>lambdaQuery()
                        .eq(MedicationPlan::getElderId, elderId)
                        .eq(MedicationPlan::getStatus, "ACTIVE")
                        .orderByAsc(MedicationPlan::getRemindTime))
                .stream()
                .map(m -> new HealthDtos.MedicationView(m.getId(), m.getDrugName(), m.getDosage(),
                        m.getFrequency(), m.getRemindTime(), m.getStatus()))
                .toList();
    }

    /** 用药计划由老人或已绑定家属维护（HC-08 支持家属代设置）。 */
    @Transactional
    public HealthDtos.MedicationView createMedication(Long elderId, HealthDtos.MedicationRequest req,
                                                      LoginUser user) {
        accessService.requireReadable(elderId, user);
        MedicationPlan plan = new MedicationPlan();
        plan.setElderId(elderId);
        plan.setDrugName(req.drugName());
        plan.setDosage(req.dosage());
        plan.setFrequency(req.frequency());
        plan.setRemindTime(req.remindTime());
        plan.setStatus("ACTIVE");
        plan.setCreatedBy(user.userId());
        plan.setCreatedAt(LocalDateTime.now());
        medicationMapper.insert(plan);
        return new HealthDtos.MedicationView(plan.getId(), plan.getDrugName(), plan.getDosage(),
                plan.getFrequency(), plan.getRemindTime(), plan.getStatus());
    }

    @Transactional
    public void stopMedication(Long planId, LoginUser user) {
        MedicationPlan plan = medicationMapper.selectById(planId);
        if (plan == null) {
            throw BizException.notFound("用药计划不存在");
        }
        accessService.requireReadable(plan.getElderId(), user);
        plan.setStatus("STOPPED");
        medicationMapper.updateById(plan);
    }
}
