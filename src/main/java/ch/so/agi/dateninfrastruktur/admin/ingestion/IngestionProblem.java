package ch.so.agi.dateninfrastruktur.admin.ingestion;
/** Stable safe API failures; no exception/SQL/CSV/credential text crosses the boundary. */
public final class IngestionProblem extends RuntimeException {
    private final int status;private final String code;
    public IngestionProblem(int status,String code,String safe){super(safe);this.status=status;this.code=code;}
    public int status(){return status;}public String code(){return code;}
    public static IngestionProblem forbidden(){return new IngestionProblem(403,"NOT_AUTHORIZED","Für diesen Vorgang fehlt die aktuelle Berechtigung.");}
    public static IngestionProblem conflict(String code){return new IngestionProblem(409,code,"Der Auftrag oder sein Vertrag hat sich geändert.");}
    public static IngestionProblem invalid(String code){return new IngestionProblem(400,code,"Die Angaben sind ungültig.");}
}
