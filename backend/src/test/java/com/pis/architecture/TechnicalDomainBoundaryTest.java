package com.pis.architecture;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

/** Source-reference guard for the nine workflow domains; not a full bytecode architecture analyzer. */
class TechnicalDomainBoundaryTest {
    @Test void technicalDomainUsesPublicBoundariesAndAddsNoWorkflowDomainCycle() throws Exception {
        var domains=List.of("accession","grossing","processing","specimen","label","material","quality","worklist","diagnosis");
        var references=Pattern.compile("com\\.pis\\.(accession|grossing|processing|specimen|label|material|quality|worklist|diagnosis)\\.([A-Z][A-Za-z0-9]*)");
        Map<String,Set<String>> edges=new HashMap<>();
        for(String domain:domains) {
            edges.put(domain,new HashSet<>());
            try(var files=Files.walk(Path.of("src/main/java/com/pis",domain))) {
                for(var file:files.filter(p->p.toString().endsWith(".java")).toList()) {
                    var matcher=references.matcher(Files.readString(file));
                    while(matcher.find()) {
                        String other=matcher.group(1); if(other.equals(domain)) continue;
                        edges.get(domain).add(other);
                        if(domain.equals("processing")) assertThat(other+"."+matcher.group(2)).as("public boundary used by %s",file)
                            .isIn("accession.RequestService","accession.WorkflowAccess","grossing.GrossService","quality.QualityGate");
                    }
                }
            }
        }
        for(String domain:domains) assertAcyclic(domain,edges,new HashSet<>());
    }
    private void assertAcyclic(String domain,Map<String,Set<String>> edges,Set<String> path) {
        assertThat(path.add(domain)).as("workflow dependency cycle at %s",domain).isTrue();
        for(String target:edges.get(domain)) assertAcyclic(target,edges,new HashSet<>(path));
    }
}
