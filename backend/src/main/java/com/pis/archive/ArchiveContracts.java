package com.pis.archive;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.time.Instant;
import java.util.*;
public final class ArchiveContracts {
 private ArchiveContracts() { }
 public enum Action { LOCATION, REGISTER, MOVE, REMOVE, LOAN, APPROVE, REJECT, CANCEL, CHECKOUT, RETURN, DAMAGE, LOST, FOUND, INVENTORY, CHECK, CORRECT }
 public record Selection(@NotNull UUID id,@NotNull @Min(0) Long version) { }
 public record Command(@NotNull UUID confirmedRequestId,@NotNull @Min(-1) Long expectedVersion,@NotBlank @Size(max=2000) String reason,
  UUID itemId,@Min(0) Long itemVersion,@Size(max=100) String barcode,UUID sourceId,@Min(0) Long sourceVersion,
  UUID locationId,@Pattern(regexp="SYN-[A-Z0-9-]{1,60}") String code,@Pattern(regexp="SYN-[A-Z0-9-]{1,60}") String policyLabel,Boolean legalHold,
  UUID loanId,UUID borrowerId,@Size(max=1000) String purpose,Instant dueAt,@NotNull @Size(max=20) List<@NotNull @Valid Selection> items,
  UUID inventoryId,UUID referenceId,@Pattern(regexp="MATCH|DIFFERENCE") String observation) { }
 public record Source(UUID id,String kind,String barcode,long version,String status,UUID revisionId,String hash) { }
 public record Item(UUID id,String kind,UUID sourceId,long sourceVersion,String barcode,UUID revisionId,String hash,long version,UUID locationId,String condition,String policyLabel,boolean legalHold,String currentSourceStatus) { }
 public record Location(UUID id,String code) { }
 public record Loan(UUID id,UUID applicantId,UUID borrowerId,UUID approverId,String purpose,Instant dueAt,String state) { }
 public record LoanItem(UUID loanId,UUID itemId,String state) { }
 public record Inventory(UUID id,long bookVersion,Instant recordedAt) { }
 public record Snapshot(UUID itemId,long itemVersion,UUID locationId,String condition,String loanState) { }
 public record Event(UUID id,long version,Action action,UUID itemId,UUID loanId,UUID inventoryId,UUID referenceId,UUID actorId,String reason,String detail,Instant recordedAt,String physicalConfirmation) { }
 public record View(UUID requestId,UUID caseId,UUID patientId,String number,long version,UUID actorId,boolean canRequest,boolean canApprove,boolean canManage,int maxLoanDays,List<Source> sources,List<Item> items,List<Location> locations,List<Loan> loans,List<LoanItem> loanItems,List<Inventory> inventories,UUID inventoryId,List<Snapshot> snapshot,List<Event> events,int page) { }
 public record BatchEntry(@NotBlank @Size(max=128) String key,@NotNull Action action,@NotNull @Valid Command command) { }
 public record Batch(@NotNull @Size(min=1,max=10) List<@NotNull @Valid BatchEntry> entries) { }
 public record BatchResult(int index,String status,String code,com.pis.idempotency.IdempotentCommands.Result result) { }
}
