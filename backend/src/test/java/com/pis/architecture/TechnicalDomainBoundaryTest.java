package com.pis.architecture;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

/** Source-reference guard for workflow domains; not a full bytecode architecture analyzer. */
class TechnicalDomainBoundaryTest {
    private static final List<String> DOMAINS=List.of("accession","grossing","processing","specimen","label","material","quality","worklist","diagnosis","report","integration","frozen","archive");
    // Include wildcard imports: a domain cannot conceal an edge by shortening its imports.
    private static final Pattern REFERENCES=Pattern.compile("com\\.pis\\.("+String.join("|",DOMAINS)+")\\.([A-Z][A-Za-z0-9]*|\\*)");
    @Test void technicalDomainUsesPublicBoundariesAndAddsNoWorkflowDomainCycle() throws Exception {
        Map<String,Set<String>> edges=new HashMap<>();
        for(String domain:DOMAINS) {
            edges.put(domain,new HashSet<>());
            try(var files=Files.walk(Path.of("src/main/java/com/pis",domain))) {
                for(var file:files.filter(p->p.toString().endsWith(".java")).toList()) {
                    var matcher=REFERENCES.matcher(Files.readString(file));
                    while(matcher.find()) {
                        String other=matcher.group(1); if(other.equals(domain)) continue;
                        edges.get(domain).add(other);
                        if(domain.equals("processing")) assertThat(other+"."+matcher.group(2)).as("public boundary used by %s",file)
                            .isIn("accession.RequestService","accession.WorkflowAccess","grossing.GrossService","quality.QualityGate");
                    }
                }
            }
        }
        for(String domain:DOMAINS) assertAcyclic(domain,edges,new ArrayList<>());
        assertThat(edges.get("integration")).contains("report");
        assertThat(edges.get("report")).doesNotContain("integration");
    }
    @Test void wildcardAndQualifiedReferencesRemainVisibleToTheBoundaryGuard() {
        var matcher=REFERENCES.matcher("import com.pis.report.*; com.pis.integration.CaAdapter unavailable;");
        Set<String> dependencies=new HashSet<>();
        while(matcher.find()) dependencies.add(matcher.group(1));
        assertThat(dependencies).containsExactlyInAnyOrder("report","integration");
    }
    private void assertAcyclic(String domain,Map<String,Set<String>> edges,List<String> path) {
        var complete=new ArrayList<>(path);complete.add(domain);
        assertThat(path).as("workflow dependency cycle: %s",String.join(" -> ",complete)).doesNotContain(domain);
        for(String target:edges.get(domain)) assertAcyclic(target,edges,new ArrayList<>(complete));
    }
}
