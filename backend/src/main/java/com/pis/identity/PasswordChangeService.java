package com.pis.identity;

import com.pis.api.ApiException;
import com.pis.audit.AuditRecorder;
import com.pis.audit.CurrentActor;
import jakarta.validation.Validator;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class PasswordChangeService {
    private final JdbcTemplate jdbc;private final CurrentActor actors;private final PasswordEncoder passwords;
    private final AuditRecorder audit;private final Validator validator;private final TransactionTemplate transaction;
    public PasswordChangeService(JdbcTemplate jdbc,CurrentActor actors,PasswordEncoder passwords,AuditRecorder audit,Validator validator,PlatformTransactionManager manager){
        this.jdbc=jdbc;this.actors=actors;this.passwords=passwords;this.audit=audit;this.validator=validator;transaction=new TransactionTemplate(manager);transaction.setTimeout(10);
    }
    public void change(IdentityContracts.ChangePassword body){
        var failures=validator.validate(body);if(!failures.isEmpty())throw new jakarta.validation.ConstraintViolationException(failures);
        IdentityAdministration.password(body.newPassword());var actor=actors.require();
        String old=jdbc.queryForObject("SELECT password_hash FROM app_user WHERE id=?",String.class,actor.id());
        if(!passwords.matches(body.oldPassword(),old)||passwords.matches(body.newPassword(),old))throw new ApiException(HttpStatus.BAD_REQUEST,"PASSWORD_CHANGE_REJECTED","Password change rejected");
        String encoded=passwords.encode(body.newPassword());
        transaction.executeWithoutResult(status->{
            jdbc.execute("SET LOCAL lock_timeout='3s'");jdbc.queryForList("SELECT id FROM app_user WHERE id=? FOR UPDATE",actor.id());actors.require();
            var hospitals=jdbc.query("SELECT hospital_id FROM identity_account WHERE user_id=?",(r,i)->r.getObject(1,UUID.class),actor.id());
            if(hospitals.isEmpty())throw new ApiException(HttpStatus.CONFLICT,"ACCOUNT_NOT_MANAGED","Account is not managed");
            // Append using the still-current actor, then invalidate every session atomically with the hash.
            audit.append(hospitals.getFirst(),"IDENTITY_PASSWORD_CHANGE_V1","IDENTITY_USER",actor.id(),actor.authVersion(),actor.authVersion()+1);
            if(jdbc.update("UPDATE app_user SET password_hash=?,password_change_required=false,auth_version=auth_version+1 WHERE id=? AND auth_version=? AND password_hash=?",encoded,actor.id(),actor.authVersion(),old)!=1)throw new ApiException(HttpStatus.CONFLICT,"ADMIN_VERSION_CONFLICT","Account changed");
            jdbc.update("UPDATE identity_account SET version=version+1 WHERE user_id=?",actor.id());
            jdbc.update("INSERT INTO identity_change_event(id,hospital_id,actor_id,target_id,action,reason,result_version) VALUES(?,?,?,?,'PASSWORD_CHANGE','用户修改本人密码',?)",UUID.randomUUID(),hospitals.getFirst(),actor.id(),actor.id(),actor.authVersion()+1);
        });
    }
}
