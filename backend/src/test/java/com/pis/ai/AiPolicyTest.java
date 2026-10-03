package com.pis.ai;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import static com.pis.ai.AiContracts.*;
import static org.assertj.core.api.Assertions.*;
class AiPolicyTest {
 static Metadata metadata(String evidence,String calibration){return new Metadata("a".repeat(64),"SYN-PRE-1","SYN-AI-CONFIG-1",16,"SYN-RGB-PYRAMID-1","SYN-PATH","SYN-STAIN","SYN-SCANNER","SYN-V1","ALL_DIGITAL_QC_PASS",calibration,"SYNTHETIC_CONTRACT_ONLY",evidence,evidence.equals("MISSING")?"":"SYN-REFERENCE","Synthetic contract only; no performance evidence");}
 static Model model(String state,long head,Metadata m){return new Model(UUID.randomUUID(),UUID.randomUUID(),UUID.randomUUID(),"SYN-MODEL",0,head,1,state,m);}
 @Test void unknownMetadataAndCalibrationNeverPass(){var m=model("VALIDATION_ONLY",0,metadata("MISSING","SYNTHETIC_XY_MPP_REQUIRED"));var r=AiPolicy.decide(m,new Profile("UNKNOWN","UNKNOWN","UNKNOWN"),null,false);assertThat(r.outcome()).isEqualTo("NEEDS_REVIEW");assertThat(r.reasons()).containsExactly("VALIDATION_EVIDENCE_MISSING","PATHOLOGY_UNKNOWN","STAIN_UNKNOWN","SCANNER_VERSION_UNKNOWN","SCANNER_UNKNOWN","CALIBRATION_MISSING");}
 @Test void exactSyntheticContractOnlyIsDeterministicAndNotExecution(){var m=model("VALIDATION_ONLY",0,metadata("DECLARED_SYNTHETIC","PIXEL_ONLY"));var p=new Profile("SYN-PATH","SYN-STAIN","SYN-V1");assertThat(AiPolicy.decide(m,p,"SYN-SCANNER",false)).isEqualTo(AiPolicy.decide(m,p,"SYN-SCANNER",false));assertThat(AiPolicy.decide(m,p,"SYN-SCANNER",false).reasons()).containsExactly("SYNTHETIC_CONTRACT_ONLY_NO_EXECUTION");assertThat(AiPolicy.decide(m,p,"SYN-OTHER",false).outcome()).isEqualTo("NOT_APPLICABLE");}
 @Test void everyNonValidationStateAndSupersededVersionRejects(){var p=new Profile("SYN-PATH","SYN-STAIN","SYN-V1");for(var state:new String[]{"DRAFT","DISABLED","RETIRED"})assertThat(AiPolicy.decide(model(state,0,metadata("DECLARED_SYNTHETIC","PIXEL_ONLY")),p,"SYN-SCANNER",false).outcome()).isEqualTo("NOT_APPLICABLE");assertThat(AiPolicy.decide(model("VALIDATION_ONLY",1,metadata("DECLARED_SYNTHETIC","PIXEL_ONLY")),p,"SYN-SCANNER",false).reasons()).contains("MODEL_SUPERSEDED");}
 @Test void unknownSchemaAndClinicalApprovalAreRejected(){var m=metadata("MISSING","PIXEL_ONLY");AiPolicy.validate(m);var bad=new Metadata(m.digest(),m.preprocessing(),"UNKNOWN",16,m.inputContract(),m.pathology(),m.stain(),m.scannerCode(),m.scannerVersion(),m.quality(),m.calibration(),"CLINICAL_APPROVED",m.evidence(),m.evidenceRef(),m.limitations());assertThatThrownBy(()->AiPolicy.validate(bad)).isInstanceOf(IllegalArgumentException.class);}
}
