package gb.extension;

import org.slf4j.Logger;

import com.thingworx.contentmanagement.ImportedEntityCollection;
import com.thingworx.entities.utils.EntityUtilities;
import com.thingworx.logging.LogUtilities;
import com.thingworx.migration.ExtensionMigratorBase;
import com.thingworx.relationships.RelationshipTypes.ThingworxRelationshipTypes;
import com.thingworx.things.Thing;

public class ExtMigrator extends ExtensionMigratorBase {

	private static Logger _logger = LogUtilities.getInstance().getApplicationLogger(ExtMigrator.class);

	@Override
	public void migrate(ImportedEntityCollection imports) throws Exception {
		// Migrated from any other version to 2.3.0
		Thing thing = (Thing) EntityUtilities.findEntity("GIT.Utility.Thing", ThingworxRelationshipTypes.Thing);
		thing.processServiceRequest("InitUserExtensionProperties", null);
		_logger.warn("Performed one-time migration to 2.3.0.");

		// Migrate to 5.2.0: Initialize GpgKeys UserExtension property for all users
		thing.processServiceRequest("InitUserExtensionGpgKeysProperty", null);
		_logger.warn("Performed one-time migration to 5.2.0: GpgKeys UserExtension property initialized.");
	}

}
