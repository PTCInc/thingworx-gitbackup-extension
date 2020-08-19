package gb;

import com.thingworx.entities.utils.EntityUtilities;
import com.thingworx.logging.LogUtilities;
import com.thingworx.metadata.annotations.ThingworxServiceDefinition;
import com.thingworx.metadata.annotations.ThingworxServiceParameter;
import com.thingworx.metadata.annotations.ThingworxServiceResult;
import com.thingworx.relationships.RelationshipTypes.ThingworxRelationshipTypes;
import com.thingworx.resources.Resource;
import com.thingworx.things.repository.FileRepositoryThing;


import org.slf4j.Logger;

public class GitBackupValidation extends Resource {

	/**
	 * 
	 */
	private static final long serialVersionUID = 9085129963750550673L;
	private static Logger _logger = LogUtilities.getInstance().getApplicationLogger(GitBackupValidation.class);
	

	public GitBackupValidation() {
		// TODO Auto-generated constructor stub
	}

	//This service checks if a FileRepository's getRootPath returns an absolute path or not.
	//The extension will not work if that method does not return an absolute path.
	//Note: due to security reasons, this should not return an absolute path, just the result of the check.
	@ThingworxServiceDefinition(name = "CheckConfiguration", description = "", category = "", isAllowOverride = false, aspects = {
			"isAsync:false" })
	@ThingworxServiceResult(name = "Result", description = "", baseType = "STRING", aspects = {})
	public String CheckConfiguration(
			@ThingworxServiceParameter(name = "FileRepository", description = "", baseType = "THINGNAME", aspects = {
					"defaultValue:GitRepository" }) String str_FileRepository) {
		_logger.trace("Entering Service: CheckConfiguration");
		String str_Result = "";
		FileRepositoryThing srcRepo = (FileRepositoryThing) EntityUtilities.findEntity(str_FileRepository,
				ThingworxRelationshipTypes.Thing);
		if (srcRepo.getRootPath().length()!=0)
		{
			str_Result= "Success! The system has a correctly configured file repository path.";
		}
		else str_Result= "Failed! The system does not contain a correctly configured file repository path. Make sure you are not using relative paths in the platform-settings.json file.";
		
		_logger.trace("Exiting Service: CheckConfiguration");
		return str_Result;
	}

}
