package com.pis.core;

import com.pis.api.ApiException;
import java.util.UUID;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Public identity boundary. The authorized synthetic use case owns the transaction. */
@Service
public class ManualIdentityRegistration {
    private final JdbcTemplate jdbc;
    public ManualIdentityRegistration(JdbcTemplate jdbc) { this.jdbc=jdbc; }
    public record Identity(UUID patientId, UUID encounterId) { }
    @Transactional(propagation=Propagation.MANDATORY)
    public Identity register(UUID hospitalId, UUID departmentId, UUID sourceId, String name, String number) {
        if (name==null || name.isBlank() || !name.equals(name.strip()) || name.length()>255
            || number==null || number.isBlank() || !number.equals(number.strip()) || number.length()>255) {
            throw new ApiException(HttpStatus.BAD_REQUEST,"MANUAL_IDENTITY_INVALID","Invalid manual identity input");
        }
        UUID patient=UUID.randomUUID(), encounter=UUID.randomUUID();
        jdbc.update("INSERT INTO patient(id,hospital_id,display_name) VALUES(?,?,?)",patient,hospitalId,name);
        try {
            jdbc.update("INSERT INTO encounter(id,hospital_id,patient_id,source_system_id,encounter_number,department_id) VALUES(?,?,?,?,?,?)",
                encounter,hospitalId,patient,sourceId,number,departmentId);
        } catch (DuplicateKeyException error) {
            throw new ApiException(HttpStatus.CONFLICT,"ENCOUNTER_NUMBER_EXISTS","This encounter number already exists; select the existing encounter");
        }
        return new Identity(patient,encounter);
    }
}
