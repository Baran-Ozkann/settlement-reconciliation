-- A statement file is recorded once, in its final state (V6): INGESTED with its line count and
-- error summary, which are known only after the last line has been read. Ingestion reads the file
-- once, storing lines as it goes, so the file row can only be written after its lines, in the same
-- transaction (FR-ING-6).
--
-- The foreign keys from the lines to their file become DEFERRABLE, still INITIALLY IMMEDIATE: every
-- statement is checked at once exactly as before, unless a transaction asks otherwise with
-- SET CONSTRAINTS ... DEFERRED. Only the ingestion transaction does, and then the check runs at its
-- commit, which is refused if the file row was never written. A line without its file can
-- therefore still never be committed. Deferring needs no privilege, so recon_app gains none.
ALTER TABLE psp_lines ALTER CONSTRAINT psp_lines_file_fk DEFERRABLE INITIALLY IMMEDIATE;
ALTER TABLE bank_lines ALTER CONSTRAINT bank_lines_file_fk DEFERRABLE INITIALLY IMMEDIATE;
