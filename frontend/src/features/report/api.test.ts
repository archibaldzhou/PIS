import { expect, it } from 'vitest';
import { parseRevision } from './api';
const r = { id: 'revision', caseId: 'case', version: 0, templateCode: 'SYN-REPORT', templateVersion: 1, assignmentVersion: 0, authorId: 'actor', reason: 'Synthetic', createdAt: '2026-10-03T00:00:00Z', fields: { gross: '', microscopy: '', diagnosis: 'Synthetic manual', notes: '' } };
it('binds draft snapshot to exact case and template version', () => { expect(parseRevision(r, 'case').templateVersion).toBe(1); expect(() => parseRevision(r, 'foreign')).toThrow(); });
it('rejects unknown or mistyped fields without coercion', () => { for (const fields of [{ ...r.fields, sampleCount: '1', manualChecked: true }, { ...r.fields, diagnosis: false }, { ...r.fields, signed: true }]) expect(() => parseRevision({ ...r, fields }, 'case')).toThrow(); });
it('retains empty partial draft text and explicit false boolean', () => { expect(parseRevision({ ...r, fields: { ...r.fields, diagnosis: '', sampleCount: 0, manualChecked: false } }, 'case').fields.manualChecked).toBe(false); });
