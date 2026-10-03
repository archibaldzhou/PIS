package com.pis.ai;
import java.util.*;
import static com.pis.ai.AiContracts.*;
/** Deterministic contract checks, never an inference permission or diagnostic algorithm. */
public final class AiPolicy {
 private AiPolicy(){}
 public static void validate(Metadata m){if(!"SYN-AI-CONFIG-1".equals(m.configSchema())||!"SYN-RGB-PYRAMID-1".equals(m.inputContract())||!"ALL_DIGITAL_QC_PASS".equals(m.quality())||!Set.of("PIXEL_ONLY","SYNTHETIC_XY_MPP_REQUIRED").contains(m.calibration())||!"SYNTHETIC_CONTRACT_ONLY".equals(m.approvalScope())||!Set.of("MISSING","DECLARED_SYNTHETIC").contains(m.evidence())||("MISSING".equals(m.evidence())?!m.evidenceRef().isEmpty():!m.evidenceRef().matches("SYN-[A-Za-z0-9_-]{1,100}")))throw new IllegalArgumentException("AI_SCHEMA");}
 public record Result(String outcome,List<String> reasons){}
 public static Result decide(Model m,Profile p,String scanner,boolean calibrated){
  var reject=new ArrayList<String>();var unknown=new ArrayList<String>();
  if(m.ordinal()!=m.head())reject.add("MODEL_SUPERSEDED");if(!m.state().equals("VALIDATION_ONLY"))reject.add("MODEL_"+m.state());
  if(m.metadata().evidence().equals("MISSING"))unknown.add("VALIDATION_EVIDENCE_MISSING");
  compare(p.pathology(),m.metadata().pathology(),"PATHOLOGY",reject,unknown);compare(p.stain(),m.metadata().stain(),"STAIN",reject,unknown);compare(p.scannerVersion(),m.metadata().scannerVersion(),"SCANNER_VERSION",reject,unknown);compare(scanner,m.metadata().scannerCode(),"SCANNER",reject,unknown);
  if(m.metadata().calibration().equals("SYNTHETIC_XY_MPP_REQUIRED")&&!calibrated)unknown.add("CALIBRATION_MISSING");
  if(!reject.isEmpty()){reject.addAll(unknown);return new Result("NOT_APPLICABLE",List.copyOf(reject));}
  return unknown.isEmpty()?new Result("VALIDATION_ONLY_APPLICABLE",List.of("SYNTHETIC_CONTRACT_ONLY_NO_EXECUTION")):new Result("NEEDS_REVIEW",List.copyOf(unknown));
 }
 private static void compare(String actual,String expected,String field,List<String> reject,List<String> unknown){if(actual==null||actual.equals("UNKNOWN"))unknown.add(field+"_UNKNOWN");else if(!actual.equals(expected))reject.add(field+"_MISMATCH");}
}
