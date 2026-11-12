package ch.so.agi.dateninfrastruktur.admin.ingestion;

import java.util.*;
import org.apache.cayenne.ObjectContext;
import org.apache.cayenne.query.ObjectSelect;
import ch.so.agi.dateninfrastruktur.admin.access.AuthorityService;
import ch.so.agi.dateninfrastruktur.admin.audit.*;
import ch.so.agi.dateninfrastruktur.admin.identity.PrincipalRepository;
import ch.so.agi.dateninfrastruktur.admin.model.*;
import ch.so.agi.dateninfrastruktur.admin.persistence.*;

/** Small approved-contract configuration, not an arbitrary schema/script editor. */
public final class DeliveryConfigurationService {
    public record Update(long expectedVersion,Boolean enabled,Boolean automaticPublication,String definitionKey,String allowedModes,UUID schemaId){}
    private final AuditedUnitOfWork unit;private final AuthorityService authority;private final CsvDefinitionRegistry registry;
    private final AggregateWriteGuard guard=new AggregateWriteGuard();private final CommandReceiptRepository receipts=new CommandReceiptRepository();
    public DeliveryConfigurationService(AuditedUnitOfWork unit,AuthorityService authority,CsvDefinitionRegistry registry){this.unit=unit;this.authority=authority;this.registry=registry;}
    public UUID update(ActorContext actor,CommandContext command,UUID datasetId,Update input){
        if(!"csv.configure".equals(command.actionCode()) || input.enabled()==null || input.automaticPublication()==null || !Set.of("replace_only","append_only","replace_and_append").contains(input.allowedModes()))throw IngestionProblem.invalid("INVALID_INPUT");
        var approved=registry.resolve(input.definitionKey());
        return unit.execute(actor,command,context->{
            new PrincipalRepository().requireActive(context,actor);authority.require(context,actor,datasetId,"manage_metadata");
            var dataset=ObjectSelect.query(Dataset.class).where(Dataset.T_ILI_TID.eq(datasetId)).selectOne(context);
            if(dataset==null)throw IngestionProblem.forbidden();
            var oldConfiguration=ObjectSelect.query(Deliveryconfiguration.class).where(Deliveryconfiguration.DATASET1.eq(dataset)).selectOne(context);
            if ("published".equals(dataset.getPublicationstatus()) || input.automaticPublication()) {
                authority.require(context, actor, datasetId, "publish");
            }
            var old=receipts.findOrReserve(context,actor,command,CanonicalJson.digest(Map.of("datasetId",datasetId,"configuration",input)));if(old!=null)return old.reference();
            guard.lockDataset(context,datasetId,input.expectedVersion());
            authority.require(context,actor,datasetId,"manage_metadata");
            if("published".equals(dataset.getPublicationstatus()) || input.automaticPublication())authority.require(context,actor,datasetId,"publish");
            Dataschema schema;
            if(input.schemaId()==null)schema=createSchema(context,approved.definition());
            else{
                schema=ObjectSelect.query(Dataschema.class).where(Dataschema.T_ILI_TID.eq(input.schemaId())).selectOne(context);
                if(schema==null)throw IngestionProblem.invalid("CONFIGURATION_CHANGED");
                verifySchema(schema,approved.definition());
            }
            for (var issue : dataset.getDatasetissues()) issue.setAschema1(schema);
            var configuration = oldConfiguration == null ? context.newObject(Deliveryconfiguration.class) : oldConfiguration;
            if (oldConfiguration == null) {
                configuration.setTIliTid(UUID.randomUUID());
                configuration.setTBasket(dataset.getTBasket());
                configuration.setDataset1(dataset);
            }
            configuration.setEnabled(input.enabled());
            configuration.setAformat("csv");
            configuration.setAllowedmodes(input.allowedModes());
            receipts.complete(context,actor,command,new CommandReceiptRepository.Receipt(configuration.getTIliTid(),"COMMITTED"));return configuration.getTIliTid();
        });
    }
    private static Dataschema createSchema(ObjectContext context,CsvDefinitionRegistry.Definition definition){
        var schema=context.newObject(Dataschema.class);schema.setTIliTid(UUID.randomUUID());schema.setIdentifier("csv-"+definition.definitionKey()+"-"+schema.getTIliTid());schema.setModelname(definition.modelName());schema.setModelversion(definition.modelVersion());schema.setAkind("simple_csv");schema.setTBasket(ManagementBaskets.require(context, ManagementBaskets.Topic.Catalog));
        int ordinal=1;for(var column:definition.columns()){
            var attribute=context.newObject(Attributedefinition.class);attribute.setTIliTid(UUID.randomUUID());attribute.setTBasket(schema.getTBasket());attribute.setAname(column.attribute());attribute.setDatatype(column.type());attribute.setOrdinal(ordinal++);attribute.setMandatory(column.mandatory());attribute.setAschema1(schema);
        }
        return schema;
    }
    private static void verifySchema(Dataschema schema,CsvDefinitionRegistry.Definition definition){
        if(!definition.modelName().equals(schema.getModelname()) || !definition.modelVersion().equals(schema.getModelversion()))throw IngestionProblem.conflict("CONFIGURATION_CHANGED");
        var attributes=schema.getAttributedefinitions().stream().sorted(Comparator.comparingInt(Attributedefinition::getOrdinal)).toList();
        if(attributes.size()!=definition.columns().size())throw IngestionProblem.conflict("CONFIGURATION_CHANGED");
        for(int i=0;i<attributes.size();i++){
            var a=attributes.get(i);var c=definition.columns().get(i);
            if(a.getOrdinal()!=i+1 || !a.getAname().equals(c.attribute()) || !a.getDatatype().equals(c.type()) || a.isMandatory()!=c.mandatory() || a.getUnit()!=null || a.getCodelist()!=null)throw IngestionProblem.conflict("CONFIGURATION_CHANGED");
        }
    }
}
