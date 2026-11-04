package ch.so.agi.dateninfrastruktur.admin.authorization;
import javax.sql.DataSource;
import java.util.Optional;
import java.util.UUID;
import ch.so.agi.dateninfrastruktur.admin.audit.ActorContext;

/** Exactly one fresh primary-PostgreSQL statement; no user-right or classification cache. */
public final class JdbcAuthorizationRepository {
    public record Decision(int status,String reason,UUID datasetId,UUID distributionId,String fileName){}
    private final DataSource source;
    public JdbcAuthorizationRepository(DataSource source){this.source=source;}
    public Decision evaluate(Optional<ActorContext> identity,DownloadPathResolver.DownloadTarget target){
        String sql="""
          WITH di_now AS MATERIALIZED (SELECT timezone('UTC',clock_timestamp()) AS observed_at),
          target AS (
            SELECT d.t_id AS dataset_pk,d.t_ili_tid AS dataset_id,d.accesslevel,x.t_ili_tid AS distribution_id,x.filename
            FROM di_management.distribution x JOIN di_management.dataversion v ON v.t_id=x.version
            JOIN di_management.datasetissue i ON i.t_id=v.issue JOIN di_management.dataset d ON d.t_id=i.dataset
            WHERE x.t_ili_tid=? AND x.filename=? AND x.availability='available' AND v.availability='available'
              AND i.publicationstatus='published' AND d.publicationstatus='published'),
          actor AS (SELECT t_id FROM di_management.principal WHERE aissuer=? AND asubject=?
            AND aactive AND akind IN ('user','service')),
          eligible AS (SELECT t_id FROM actor UNION
            SELECT m.agroup FROM di_management.groupmembership m JOIN actor a ON a.t_id=m.amember
            JOIN di_management.principal g ON g.t_id=m.agroup CROSS JOIN di_now n
            WHERE g.akind='group' AND g.aactive AND m.revokedat IS NULL AND m.validfrom<=n.observed_at
              AND (m.validuntil IS NULL OR n.observed_at<m.validuntil))
          SELECT t.dataset_id,t.distribution_id,t.filename,
            CASE WHEN t.accesslevel='open' THEN 204 WHEN NOT ? THEN 401
              WHEN EXISTS(SELECT 1 FROM di_management.accessgrant grant_row
                JOIN eligible e ON e.t_id=grant_row.beneficiary CROSS JOIN di_now n
                WHERE grant_row.dataset=t.dataset_pk AND grant_row.permission='read_data'
                  AND grant_row.revokedat IS NULL AND grant_row.validfrom<=n.observed_at
                  AND (grant_row.validuntil IS NULL OR n.observed_at<grant_row.validuntil))
              THEN 204 ELSE 403 END AS status
          FROM target t
          """;
        try(var connection=source.getConnection();var statement=connection.prepareStatement(sql)){
            connection.setReadOnly(true);
            statement.setQueryTimeout(2);
            statement.setObject(1,target.distributionId());statement.setString(2,target.fileName());
            statement.setString(3,identity.map(ActorContext::issuer).orElse(""));
            statement.setString(4,identity.map(ActorContext::subject).orElse(""));
            statement.setBoolean(5,identity.isPresent());
            try(var result=statement.executeQuery()){
                if(!result.next())return new Decision(404,"NOT_AVAILABLE",null,target.distributionId(),target.fileName());
                int status=result.getInt("status");
                return new Decision(status,status==204?"ALLOWED":status==401?"AUTHENTICATION_REQUIRED":"NO_READ_GRANT",
                    UUID.fromString(result.getString("dataset_id")),target.distributionId(),target.fileName());
            }
        }catch(java.sql.SQLException error){
            return new Decision(503,"AUTHORIZATION_UNAVAILABLE",null,target.distributionId(),target.fileName());
        }
    }
}
