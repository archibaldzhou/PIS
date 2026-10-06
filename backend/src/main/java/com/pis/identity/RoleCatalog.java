package com.pis.identity;

import java.util.*;

/** Versioned synthetic role presets. Database identifiers are server-owned constants. */
public final class RoleCatalog {
    private RoleCatalog() { }
    public record Permission(String code, String name, boolean professional, String menu) { }
    public record Role(String code, String name, List<String> permissions, boolean available) { }
    public record GrantTable(String table, String qualification, boolean validFrom, Map<String,String> columns) { }
    private static final LinkedHashMap<String,Permission> PERMISSIONS=new LinkedHashMap<>();
    private static final List<GrantTable> TABLES=new ArrayList<>();
    private static void group(String table,String qualification,boolean from,String menu,String... entries) {
        var columns=new LinkedHashMap<String,String>();
        for(String entry:entries) {
            String[] p=entry.split(":");
            PERMISSIONS.put(p[0],new Permission(p[0],p[2],p.length>3,menu)); columns.put(p[0],p[1]);
        }
        TABLES.add(new GrantTable(table,qualification,from,Collections.unmodifiableMap(columns)));
    }
    static {
        group("workflow_grant",null,true,"requests","READ:can_read:申请查询","WRITE:can_write:申请登记与提交",
            "RECEIVE:can_receive:标本接收","EXCEPTION:can_exception:接收异常处理","PRINT:can_print:标签打印","REPRINT:can_reprint:标签重打",
            "GROSS:can_gross:取材","PROCESS:can_process:技术处理","HANDOFF:can_handoff:技术交接","MATERIAL:can_material:材料谱系","QC:can_qc:技术质控");
        group("diagnosis_grant","SYN-DIAG-ASSIGNMENT-1",true,"diagnosis","ASSIGN:can_assign:诊断分配","DIAGNOSE:can_diagnose:诊断与人工报告:professional");
        group("report_review_grant","SYN-REPORT-REVIEW-1",true,"review","REVIEW:can_review:报告复核:professional","SIGN:can_simulate_sign:报告模拟签署:professional");
        group("frozen_grant","SYN-FROZEN-1",true,"frozen","FROZEN_RECORD:can_record:冰冻诊断记录:professional","FROZEN_REVIEW:can_review:冰冻复核:professional","FROZEN_QC:can_qc:冰冻技术质控");
        group("cytology_grant","SYN-CYTOLOGY-1",true,"cytology","CYTOLOGY_PREPARE:can_prepare:细胞学制备","CYTOLOGY_QC:can_qc:细胞学质控");
        group("stain_grant","SYN-STAIN-1",true,"staining","STAIN_REQUEST:can_request:申请染色","STAIN_EXECUTE:can_execute:染色执行","STAIN_QC:can_qc:染色质控");
        group("archive_grant","SYN-ARCHIVE-1",false,"archive","ARCHIVE_REQUEST:can_request:档案借阅申请","ARCHIVE_APPROVE:can_approve:档案借阅批准","ARCHIVE_MANAGE:can_manage:档案管理归还盘点");
        group("statistics_grant","SYN-STATS-1",false,"statistics","STATISTICS:all_requests:范围工作量统计","STATISTICS_DRILL:can_drill:统计明细追溯","STATISTICS_REPORT:can_report:统计报表");
        group("storage_grant","SYN-STORAGE-1",false,"storage","STORAGE:all_cases:范围原件访问","STORAGE_WRITE:can_write:原件上传","STORAGE_CAPACITY:can_capacity:存储容量");
        group("scan_grant","SYN-SCAN-1",false,"scan","SCAN:access:扫描导入");
        group("digital_qc_grant","SYN-DIGITAL-QC-1",false,"digitalqc","DIGITAL_QC:access:数字切片质控");
        group("ai_registry_grant","SYN-AI-CONTRACT-1",false,"ai","AI_REGISTER:can_register:AI契约登记","AI_VALIDATE:can_validate:AI契约验证","AI_ASSESS:can_assess:AI适用评估");
        group("ai_task_grant","SYN-CONTRACT-WORKER-1",false,"aitasks","AI_SUBMIT:can_submit:合成AI任务提交","AI_WORK:can_work:合成AI任务处理");
        group("adapter_grant","SYN-ADAPTER-1",false,"adapters","ADAPTER:access:合成接口管理");
        group("operations_grant","SYN-OPS-1",false,"operations","OPERATIONS:access:运维快照");
        group("report_output_grant","SYN-REPORT-OUTPUT-1",false,"output","REPORT_OUTPUT:access:固定报告输出与本地投递");
        PERMISSIONS.put("AUDIT",new Permission("AUDIT","本院管理审计查看",false,"audit"));
    }
    public static List<Permission> permissions() { return List.copyOf(PERMISSIONS.values()); }
    public static List<GrantTable> tables() { return List.copyOf(TABLES); }
    public static boolean professional(String code) { return PERMISSIONS.containsKey(code)&&PERMISSIONS.get(code).professional(); }
    public static boolean known(String code) { return PERMISSIONS.containsKey(code); }
    private static Role role(String code,String name,String permissions) { return new Role(code,name,List.of(permissions.split(" ")),true); }
    public static List<Role> roles() {
        return List.of(new Role("ADMIN","系统管理员",permissions().stream().filter(p->!p.professional()).map(Permission::code).toList(),true),
            role("DIRECTOR","科主任／业务负责人","READ ASSIGN STATISTICS STATISTICS_DRILL STATISTICS_REPORT ARCHIVE_APPROVE"),
            role("RECEPTION","登记接诊员","READ WRITE RECEIVE EXCEPTION PRINT REPRINT"),
            role("GROSSING","取材医师","READ GROSS PRINT REPRINT"),
            role("TECHNICIAN","常规病理技师","READ PROCESS HANDOFF MATERIAL PRINT REPRINT"),
            role("CYTOLOGY","细胞学技师","READ CYTOLOGY_PREPARE MATERIAL PRINT"),
            role("STAINING","特殊染色／免疫组化技师","READ STAIN_EXECUTE MATERIAL PRINT"),
            role("FROZEN","冰冻技师","READ PROCESS HANDOFF MATERIAL FROZEN_QC PRINT"),
            new Role("MOLECULAR","分子病理技师（功能待实现）",List.of(),false),
            role("DIAGNOSTICIAN","病理诊断医师","READ DIAGNOSE STAIN_REQUEST ARCHIVE_REQUEST"),
            role("REVIEWER","复核／签发医师","READ DIAGNOSE REVIEW SIGN REPORT_OUTPUT"),
            role("QUALITY","质量控制人员","READ QC CYTOLOGY_QC STAIN_QC DIGITAL_QC STATISTICS STATISTICS_DRILL"),
            role("ARCHIVIST","报告发放／档案人员","READ REPORT_OUTPUT ARCHIVE_REQUEST ARCHIVE_MANAGE PRINT REPRINT"),
            role("AUDITOR","审计人员","READ AUDIT"));
    }
    public static Set<String> menus(Collection<String> permissions) {
        var result=new LinkedHashSet<String>();
        for(String code:permissions) { var p=PERMISSIONS.get(code); if(p!=null) result.add(p.menu()); }
        if(permissions.contains("WRITE")) result.addAll(List.of("registration","manual"));
        if(permissions.contains("READ")) result.add("registration");
        if(permissions.contains("RECEIVE")||permissions.contains("EXCEPTION"))result.add("reception");
        if(permissions.contains("PRINT"))result.add("labels");
        if(permissions.contains("GROSS"))result.add("grossing");
        if(permissions.contains("PROCESS"))result.addAll(List.of("technical","worklist"));
        if(permissions.contains("MATERIAL"))result.add("materials");
        if(permissions.contains("QC"))result.add("quality");
        if(permissions.contains("DIAGNOSE"))result.addAll(List.of("report","consultation","amendments","viewer"));
        if(permissions.contains("REPORT_OUTPUT"))result.add("delivery");
        if(permissions.contains("REVIEW")||permissions.contains("SIGN"))result.addAll(List.of("output","delivery"));
        if(permissions.contains("STORAGE"))result.add("viewer");
        return result;
    }
}
