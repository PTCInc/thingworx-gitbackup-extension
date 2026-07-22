package gb.extension;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.lang.reflect.InvocationTargetException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.Proxy.Type;
import java.net.ProxySelector;
import java.net.SocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.Iterator;
import java.util.List;
import org.eclipse.jgit.api.CreateBranchCommand.SetupUpstreamMode;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.ListBranchCommand.ListMode;
import org.eclipse.jgit.api.PullResult;
import org.eclipse.jgit.api.ResetCommand.ResetType;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.api.errors.RefNotFoundException;
import org.eclipse.jgit.diff.DiffEntry;
import org.eclipse.jgit.diff.DiffFormatter;
import org.eclipse.jgit.internal.storage.file.WindowCache;
import org.eclipse.jgit.lib.GpgSigner;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.PersonIdent;
import org.eclipse.jgit.lib.ObjectReader;
import org.eclipse.jgit.lib.Ref;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.lib.RepositoryCache;
import org.eclipse.jgit.lib.StoredConfig;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevTree;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.storage.file.FileRepositoryBuilder;
import org.eclipse.jgit.storage.file.WindowCacheConfig;
import org.eclipse.jgit.transport.CredentialsProvider;
import org.eclipse.jgit.transport.PushResult;
import org.eclipse.jgit.transport.UsernamePasswordCredentialsProvider;
import org.eclipse.jgit.treewalk.AbstractTreeIterator;
import org.eclipse.jgit.treewalk.CanonicalTreeParser;
import org.eclipse.jgit.treewalk.EmptyTreeIterator;
import org.eclipse.jgit.treewalk.filter.PathFilter;
import org.eclipse.jgit.internal.storage.file.FileRepository;
import org.eclipse.jgit.util.FS.FileStoreAttributes;
import org.eclipse.jgit.util.io.DisabledOutputStream;
import org.joda.time.DateTime;
import org.slf4j.Logger;
import com.thingworx.data.util.InfoTableInstanceFactory;
import com.thingworx.entities.utils.EntityUtilities;
import com.thingworx.entities.utils.UserUtilities;
import com.thingworx.logging.LogUtilities;
import com.thingworx.metadata.annotations.ThingworxBaseTemplateDefinition;
import com.thingworx.metadata.annotations.ThingworxConfigurationTableDefinition;
import com.thingworx.metadata.annotations.ThingworxConfigurationTableDefinitions;
import com.thingworx.metadata.annotations.ThingworxDataShapeDefinition;
import com.thingworx.metadata.annotations.ThingworxFieldDefinition;
import com.thingworx.metadata.annotations.ThingworxServiceDefinition;
import com.thingworx.metadata.annotations.ThingworxServiceParameter;
import com.thingworx.metadata.annotations.ThingworxServiceResult;
import com.thingworx.relationships.RelationshipTypes.ThingworxRelationshipTypes;
import com.thingworx.resources.entities.EntityServices;
import com.thingworx.security.users.User;
import com.thingworx.system.ContextType;
import com.thingworx.things.Thing;
import com.thingworx.things.repository.FileRepositoryThing;
import com.thingworx.types.InfoTable;
import com.thingworx.types.collections.ValueCollection;
import com.thingworx.types.primitives.BooleanPrimitive;
import com.thingworx.types.primitives.DatetimePrimitive;
import com.thingworx.types.primitives.InfoTablePrimitive;
import com.thingworx.types.primitives.PasswordPrimitive;
import com.thingworx.types.primitives.StringPrimitive;
import com.thingworx.webservices.context.ThreadLocalContext;

@ThingworxBaseTemplateDefinition(name = "GenericThing")

@ThingworxConfigurationTableDefinitions(tables = {
		@ThingworxConfigurationTableDefinition(name = Const.str_ConfTableName, description = "", isMultiRow = false, ordinal = 0, dataShape = @ThingworxDataShapeDefinition(fields = {
				@ThingworxFieldDefinition(name = Const.str_GitRepoURL, description = "", baseType = "STRING", ordinal = 4, aspects = {
						"defaultValue:https://bitbucket.org/username/reponame", "friendlyName:Git Repo URL" }),

				@ThingworxFieldDefinition(name = Const.str_FileRepository, description = "", baseType = "THINGNAME", ordinal = 5, aspects = {
						"thingTemplate:FileRepository", "friendlyName:File Repository",
						"defaultValue: GitRepository" }),

				@ThingworxFieldDefinition(name = Const.str_RepoPathName, description = "", baseType = "STRING", ordinal = 6, aspects = {
						"friendlyName:File Repository Path", "defaultValue:/smartparking" }),

				@ThingworxFieldDefinition(name = Const.str_InitialBranch, description = "Must be the main branch setup in the remote Git repository", baseType = "STRING", ordinal = 7, aspects = {
						"friendlyName:Initial branch", "defaultValue:main" }),

				@ThingworxFieldDefinition(name = Const.str_UseProxy, description = "Should Proxy be used?", baseType = "BOOLEAN", ordinal = 8, aspects = {
						"friendlyName:Use Proxy?", "defaultValue:false" }),

				@ThingworxFieldDefinition(name = Const.str_ProxyURL, description = "The HTTP proxy used for connection to the remote; leave blank if not used ", baseType = "STRING", ordinal = 9, aspects = {
						"friendlyName:Proxy URL", "defaultValue:proxyHostName" }),
				@ThingworxFieldDefinition(name = Const.str_ProxyPort, description = "Proxy Port", baseType = "INTEGER", ordinal = 10, aspects = {
						"friendlyName:Proxy Port", "defaultValue:0" }),
				@ThingworxFieldDefinition(name = Const.str_LocalizationTokensPrefix, description = "Prefix used for exporting Localization tokens", baseType = "STRING", ordinal = 10, aspects = {
						"friendlyName:Localization Tokens Prefix", "defaultValue:prefix" }),

		// ,@ThingworxFieldDefinition(name = Const.str_DefaultProjectToExport,
		// description = "", baseType = "STRING", ordinal = 8, aspects = {
		// "friendlyName:Default Export Project" })

		})) })
public class GitBackupTemplate extends Thing {
	/**
	 * 
	 */
	// set background file system resolution to false and enable debugging
	static {
		FileStoreAttributes.setBackground(false);

	}

	private static final long serialVersionUID = -6500080561143490845L;

	// Complete git path will be calculated by concatenating the SCR absolute
	// path and the relative path
	private String str_GitRepoURL, str_FileRepository, str_FileRepoPath, str_CurrentBranchOrCommit, str_ProxyURL;
	private Integer int_ProxyPort;
	private boolean bool_isDetachedHead = false, bool_UseProxy;
	private Git gitObject;

	private static Logger _logger = LogUtilities.getInstance().getApplicationLogger(GitBackupTemplate.class);

	public GitBackupTemplate() {
	}

	@Override
	protected void stopThing(ContextType ctx) throws Exception {

		super.stopThing(null);
	}

	@Override
	public void dispose() throws Exception {
		_logger.warn("Thing entered dispose phase");
		if (!str_GitRepoURL.equals(Const.str_GitRepoURLDefaultValue)) {
			Thread.sleep(50);
			FileRepositoryThing srcRepo = (FileRepositoryThing) EntityUtilities.findEntity(str_FileRepository,
					ThingworxRelationshipTypes.Thing);
			Git myGitObject = getGitObject("dispose");
			Thread.sleep(50);
			Repository myGitRepository = myGitObject.getRepository();
			myGitRepository.close();
			myGitRepository.close();
			myGitRepository.close();
			myGitRepository.close();
			myGitRepository.close();
			Thread.sleep(50);
			myGitObject.close();
			Thread.sleep(50);
			_logger.warn("Disposing GitBackup Thing " + getName() + "; RepositoryCache contains: "
					+ RepositoryCache.getRegisteredKeys().size());
			RepositoryCache.clear();
			// System.gc();
			Thread.sleep(200);
			gitObject = null;
			String str_FolderPath = srcRepo.getRootPath();
			str_FolderPath += str_FileRepoPath;
			// delete(new File(str_FolderPath), "dispose");
			deleteGitFolder(new File(str_FolderPath), "dispose");
		}
		super.dispose();
	}

	@Override
	protected void initializeThing(ContextType ctx) throws Exception {

		// Initialize internal fields based on the Configuration Table
		this.str_GitRepoURL = ((String) getConfigurationSetting(Const.str_ConfTableName, Const.str_GitRepoURL));
		this.str_FileRepository = ((String) getConfigurationSetting(Const.str_ConfTableName, Const.str_FileRepository));
		this.str_FileRepoPath = ((String) getConfigurationSetting(Const.str_ConfTableName, Const.str_RepoPathName));
		this.str_CurrentBranchOrCommit = ((String) getConfigurationSetting(Const.str_ConfTableName,
				Const.str_InitialBranch));
		this.bool_UseProxy = ((boolean) getConfigurationSetting(Const.str_ConfTableName, Const.str_UseProxy));
		this.str_ProxyURL = ((String) getConfigurationSetting(Const.str_ConfTableName, Const.str_ProxyURL));
		this.int_ProxyPort = ((Integer) getConfigurationSetting(Const.str_ConfTableName, Const.str_ProxyPort));
		ProxySelector.setDefault(new ProxySelector() {
			@Override
			public List<Proxy> select(URI uri) {
				if (bool_UseProxy == true && str_GitRepoURL.contains(uri.getHost())) {
					return Arrays.asList(
							new Proxy(Type.HTTP, InetSocketAddress.createUnresolved(str_ProxyURL, int_ProxyPort)));
				}
				return Arrays.asList(Proxy.NO_PROXY);
			}

			@Override
			public void connectFailed(URI uri, SocketAddress sa, IOException ioe) {
				if (uri == null || sa == null || ioe == null) {
					throw new IllegalArgumentException("Arguments can't be null.");
				}
			}
		});
		WindowCacheConfig wconfig = new WindowCacheConfig();
		wconfig.setPackedGitMMAP(false);
		wconfig.install();
		_logger.warn("1. GitBackup Thing: " + this.getName() + " initialize phase 1.1. PackedGitMMAP set false");
		// prevents creation of disk folder with default values from configuration table
		// at the first initialization
		if (!str_GitRepoURL.equals(Const.str_GitRepoURLDefaultValue)) {
			_logger.warn("1. GitBackup Thing: " + this.getName()
					+ " initialize phase 1.2. GitRepoDefault contains real repository URL.");
			Git Mygit = getGitObject("initializeThing");
			String str_Branch = Mygit.getRepository().getFullBranch();
			bool_isDetachedHead = (str_Branch.indexOf("refs/heads") != -1 || str_Branch != null) ? false : true;
			str_CurrentBranchOrCommit = (str_Branch != null) ? Mygit.getRepository().getBranch()
					: Const.str_InitialBranch;
		}

		if (!this.implementsShape(Const.str_UtilityThingShapeName)) {
			EntityServices es = new EntityServices();
			_logger.warn("1. GitBackup Thing: " + this.getName()
					+ " initialize phase 1.3. GIT Repo does not contain the Utility Thing Shape. Starting adding it.");
			es.AddShapeToThing(this.getName(), Const.str_UtilityThingShapeName);
		}
		// will display the right library version in case we use directly the source
		// code
		String str_JGIT_version = org.eclipse.jgit.lib.Repository.class.getPackage().getImplementationVersion();
		str_JGIT_version = str_JGIT_version == null ? "6.10.1.202505221210-r; custom source code" : str_JGIT_version;
		_logger.warn("1. GitBackup Thing: " + this.getName() + " final initialize phase ended. Jgit library version: "
				+ str_JGIT_version);
		super.initializeThing(null);
	}

	@ThingworxServiceDefinition(name = "Push", description = "This will execute a push of all the files for the specific project. You might need to edit the global gitignore file to include file types you might want in the commit, like log files. This is usually stored in Windows in the the [user]/Documents/gitignore_global.txt ", category = "", isAllowOverride = false, aspects = {
			"isAsync:false" })
	@ThingworxServiceResult(name = "Result", description = "", baseType = "STRING", aspects = {})
	public String Push(
			@ThingworxServiceParameter(name = "Message", description = "A message that will appear in the git for this commit", baseType = "STRING") String Message)
			throws Exception, GitAPIException {
		_logger.trace("Entering Service: Push");
		String str_CurrentMethodName = "Push";
		try {
			// 1. Retrieve the GitRepository as a Git object that is needed for the next
			// operations
			Thread.sleep(1000);
			long startTimePush = System.nanoTime();
			Git myGitObject = getGitObject("Push");
			long endTimeOpenRepository = System.nanoTime();
			BigDecimal durationTimeOpenRepository = new BigDecimal(
					(double) (endTimeOpenRepository - startTimePush) / (double) 1000000)
					.setScale(3, RoundingMode.HALF_DOWN);
			User us_currentUser = UserUtilities.findUser(UserUtilities.getCurrentUser());
			ValueCollection vc_RepoCredentials = getGitRepoRemoteCredential(us_currentUser);
			if (vc_RepoCredentials.getPrimitive(Const.str_GitCommitterUser) == null) {
				return "Push Error: Missing Git credentials for this thing. Please configure credentials in the extension settings.";
			}
			String str_User = ((StringPrimitive) vc_RepoCredentials.getPrimitive(Const.str_GitCommitterUser))
					.getValue();
			String str_Password = ((PasswordPrimitive) vc_RepoCredentials.getPrimitive(Const.str_GitCommitterPassword))
					.getValue();
			String str_CommitterName = ((StringPrimitive) vc_RepoCredentials.getPrimitive(Const.str_GitCommitterName))
					.getValue();
			String str_CommitterEmail = ((StringPrimitive) vc_RepoCredentials.getPrimitive(Const.str_GitCommitterEmail))
					.getValue();
			// 2. Create the commit
			// 2.1. We add all the modified files to the commit
			myGitObject.add().addFilepattern(".").call();
			long endTimeAddFiles = System.nanoTime();
			BigDecimal durationTimeAddFiles = new BigDecimal(
					(double) (endTimeAddFiles - endTimeOpenRepository) / (double) 1000000)
					.setScale(3, RoundingMode.HALF_DOWN);
			myGitObject.add().addFilepattern(".").setUpdate(true).call();
			long endTimeAddAllFilesWithSetUpdate = System.nanoTime();
			BigDecimal durationTimeAddAllFilesWithSetUpdate = new BigDecimal(
					(double) (endTimeAddAllFilesWithSetUpdate - endTimeAddFiles) / (double) 1000000)
					.setScale(3, RoundingMode.HALF_DOWN);
			// 2.2 We submit the commit to the repository
			var commitCmd = myGitObject.commit().setAll(true).setMessage(Message)
					.setCommitter(str_CommitterName, str_CommitterEmail);

			// Check if GPG signing is configured for this repo (stored in separate GpgKeys property)
			String str_GpgPrivateKey = null;
			String str_GpgPassphrase = null;
			boolean bool_SignCommits = false;
			try {
				InfoTable iftbl_GpgKeys = ((InfoTablePrimitive) us_currentUser
						.getPropertyValue(Const.str_GpgKeys)).getValue();
				if (iftbl_GpgKeys != null) {
					ValueCollection vc_GpgFilter = new ValueCollection();
					vc_GpgFilter.put("GitThing", new StringPrimitive(this.getName()));
					ValueCollection vc_GpgKey = iftbl_GpgKeys.find(vc_GpgFilter);
					if (vc_GpgKey != null && vc_GpgKey.getPrimitive(Const.str_SignCommits) != null) {
						str_GpgPrivateKey = ((PasswordPrimitive) vc_GpgKey
								.getPrimitive(Const.str_GpgPrivateKey)).getValue();
						str_GpgPassphrase = ((PasswordPrimitive) vc_GpgKey
								.getPrimitive(Const.str_GpgKeyPassphrase)).getValue();
						bool_SignCommits = ((BooleanPrimitive) vc_GpgKey
								.getPrimitive(Const.str_SignCommits)).getValue();
					}
				}
			} catch (Exception e) {
				_logger.warn("No GpgKeys UserExtension property found; skipping GPG signing.");
			}

			PastedKeyGpgSigner gpgSigner = null;
			if (bool_SignCommits && str_GpgPrivateKey != null && !str_GpgPrivateKey.isEmpty()) {
				gpgSigner = new PastedKeyGpgSigner(str_GpgPrivateKey, str_GpgPassphrase);
				GpgSigner.setDefault(gpgSigner);
				commitCmd.setSign(true).setSigningKey(null);
				commitCmd.setCredentialsProvider(
						new UsernamePasswordCredentialsProvider(null, str_GpgPassphrase));
			}

			commitCmd.call();

			if (gpgSigner != null) {
				gpgSigner.clearSensitiveData();
			}

			long endTimeCommitToLocalRepository = System.nanoTime();
			BigDecimal durationTimeCommitToLocalRepository = new BigDecimal(
					(double) (endTimeCommitToLocalRepository - endTimeAddAllFilesWithSetUpdate) / (double) 1000000)
					.setScale(3, RoundingMode.HALF_DOWN);
			// 3. We will push the commit to the remote repository
			// 3.1. Create the credentials that are needed to authenticate to the online Git
			// repository provider
			CredentialsProvider credentialsProvider = new UsernamePasswordCredentialsProvider(str_User, str_Password);
			// 3.2. Push the changes to the online Git repository
			Iterable<PushResult> prList = myGitObject.push().setRemote("origin")
					.setCredentialsProvider(credentialsProvider).call();
			long endTimePushFinish = System.nanoTime();
			BigDecimal durationTimePushFinish = new BigDecimal(
					(double) (endTimePushFinish - endTimeCommitToLocalRepository) / (double) 1000000)
					.setScale(3, RoundingMode.HALF_DOWN);
			String str_LogResult = "";
			for (PushResult pr : prList) {
				str_LogResult += pr.getRemoteUpdates().toString();
			}
			str_LogResult += " Debug Timings (ms): #1.OpenGit: " + durationTimeOpenRepository + "#2.AddFiles: "
					+ durationTimeAddFiles + "#3.AddAllDeletedFiles: " + durationTimeAddAllFilesWithSetUpdate
					+ "#4.CommitToLocalRepository: " + durationTimeCommitToLocalRepository + "#5.Push: "
					+ durationTimePushFinish;
			Thread.sleep(2000);
			LogOperationResult(str_LogResult, str_CurrentMethodName);
			return str_LogResult;
		} catch (Exception e) {
			StringWriter errors = new StringWriter();
			e.printStackTrace(new PrintWriter(errors));
			_logger.error(errors.toString());
			LogOperationResult(errors.toString(), str_CurrentMethodName);
			return "Push Error: " + errors.toString();
		}

	}

	@ThingworxServiceDefinition(name = "VerifyGpgKey", description = "Verifies a pasted PGP private key can be loaded and used for signing. Returns the key fingerprint on success.", category = "", isAllowOverride = false, aspects = {
			"isAsync:false" })
	@ThingworxServiceResult(name = "Result", description = "", baseType = "INFOTABLE", aspects = {
			"isEntityDataShape:true", "dataShape:GitBackup.GpgKey.DataShape" })
	public InfoTable VerifyGpgKey(
			@ThingworxServiceParameter(name = "GpgPrivateKey", description = "ASCII-armored PGP private key", baseType = "STRING") String GpgPrivateKey,
			@ThingworxServiceParameter(name = "GpgKeyPassphrase", description = "Passphrase for the PGP private key", baseType = "PASSWORD") String GpgKeyPassphrase)
			throws Exception {
		String str_CurrentMethodName = "VerifyGpgKey";
		InfoTable iftbl_Result = InfoTableInstanceFactory
				.createInfoTableFromDataShape(Const.str_GpgKeyDataShapeName);
		try {
			// if no key was passed, try to read from stored GpgKeys property
			String resolvedKey = GpgPrivateKey;
			String resolvedPassphrase = GpgKeyPassphrase;
			if (GpgPrivateKey == null || GpgPrivateKey.trim().isEmpty()) {
				try {
					User us_currentUser = UserUtilities.findUser(UserUtilities.getCurrentUser());
					InfoTable iftbl_GpgKeys = ((InfoTablePrimitive) us_currentUser
							.getPropertyValue(Const.str_GpgKeys)).getValue();
					if (iftbl_GpgKeys != null) {
						ValueCollection vc_GpgFilter = new ValueCollection();
						vc_GpgFilter.put("GitThing", new StringPrimitive(this.getName()));
						ValueCollection vc_GpgKey = iftbl_GpgKeys.find(vc_GpgFilter);
						if (vc_GpgKey != null && vc_GpgKey.getPrimitive(Const.str_SignCommits) != null) {
							resolvedKey = ((PasswordPrimitive) vc_GpgKey
									.getPrimitive(Const.str_GpgPrivateKey)).getValue();
							resolvedPassphrase = ((PasswordPrimitive) vc_GpgKey
									.getPrimitive(Const.str_GpgKeyPassphrase)).getValue();
						}
					}
				} catch (Exception e) {
					_logger.warn("No stored GpgKeys found; proceeding with provided key if any.");
				}
			}
			// auto-detect base64-encoded keys for REST API calls where multi-line JSON escaping may cause issues
			String decodedKey = resolvedKey;
			if (resolvedKey != null && !resolvedKey.startsWith("-----")) {
				try {
					byte[] decoded = Base64.getDecoder().decode(resolvedKey);
					decodedKey = new String(decoded, StandardCharsets.UTF_8);
				} catch (IllegalArgumentException e) {
					// not valid base64, use as-is
				}
			}
			PastedKeyGpgSigner signer = new PastedKeyGpgSigner(decodedKey, resolvedPassphrase);
			PersonIdent committer = new PersonIdent("temp", "temp@temp.com");
			boolean canLocate = signer.canLocateSigningKey(null, committer, null);
			String fingerprint = signer.getFingerprint();

			ValueCollection vc = new ValueCollection();
			vc.put("GitThing", new StringPrimitive(this.getName()));
			vc.put("SignCommits", new BooleanPrimitive(canLocate));
			vc.put("GpgKeyFingerprint",
					new StringPrimitive(fingerprint != null ? fingerprint : "Unable to derive fingerprint"));
			iftbl_Result.addRow(vc);

			signer.clearSensitiveData();

			String str_LogResult = "GPG Key verification "
					+ (canLocate ? "succeeded" : "failed") + ". Fingerprint: "
					+ (fingerprint != null ? fingerprint : "N/A");
			LogOperationResult(str_LogResult, str_CurrentMethodName);
		} catch (Exception e) {
			StringWriter errors = new StringWriter();
			e.printStackTrace(new PrintWriter(errors));
			_logger.error(errors.toString());
			LogOperationResult(errors.toString(), str_CurrentMethodName);
			ValueCollection vc = new ValueCollection();
			vc.put("GitThing", new StringPrimitive(this.getName()));
			vc.put("SignCommits", new BooleanPrimitive(false));
			vc.put("GpgKeyFingerprint",
					new StringPrimitive("Verification error: " + e.getMessage()));
			iftbl_Result.addRow(vc);
		}
		return iftbl_Result;
	}

	@ThingworxServiceDefinition(name = "Pull", description = "Pulls the last commit to the File Repository path", category = "", isAllowOverride = false, aspects = {
			"isAsync:false" })
	@ThingworxServiceResult(name = "Result", description = "", baseType = "STRING", aspects = {})
	public String Pull(
			@ThingworxServiceParameter(name = "Force", description = "Forces a hard reset instead of a normal pull", baseType = "BOOLEAN") Boolean Force) {
		String str_CurrentMethodName = "Pull";
		try {
			_logger.warn("Starting Pull for GitBackup Thing: " + this.getName());
			Thread.sleep(500);
			Git myGitFolder = getGitObject("Pull");
			User us_currentUser = UserUtilities.findUser(UserUtilities.getCurrentUser());
			ValueCollection vc_RepoCredentials = getGitRepoRemoteCredential(us_currentUser);
			if (vc_RepoCredentials.getPrimitive(Const.str_GitCommitterUser) == null) {
				return "Pull Error: Missing Git credentials for this thing. Please configure credentials in the extension settings.";
			}
			String str_User = ((StringPrimitive) vc_RepoCredentials.getPrimitive(Const.str_GitCommitterUser))
					.getValue();
			String str_Password = ((PasswordPrimitive) vc_RepoCredentials.getPrimitive(Const.str_GitCommitterPassword))
					.getValue();
			CredentialsProvider credentialsProvider = new UsernamePasswordCredentialsProvider(str_User, str_Password);
			if (Force != null && Force == true) {
				myGitFolder.reset().setMode(ResetType.HARD).call();
			}
			PullResult pr = myGitFolder.pull().setCredentialsProvider(credentialsProvider).call();
			String str_LogResult = (pr.isSuccessful() == true ? "Successful. " : "Unsuccessful.") + pr.toString();
			Thread.sleep(2000);
			LogOperationResult(str_LogResult, str_CurrentMethodName);
			_logger.warn("Finished Pull for GitBackup Thing: " + this.getName());
			return str_LogResult;
		} catch (Exception e)

		{
			StringWriter errors = new StringWriter();
			e.printStackTrace(new PrintWriter(errors));
			_logger.error(errors.toString());
			try {
				LogOperationResult(errors.toString(), str_CurrentMethodName);
			} catch (Exception e1) {
				StringWriter errors2 = new StringWriter();
				e1.printStackTrace(new PrintWriter(errors));
				_logger.error(errors2.toString());
			}
			return "Pull Error: " + errors.toString();

		}

	}

	@ThingworxServiceDefinition(name = "DeleteLocalRepoContent", description = "Deletes all files from the local repo path including the git configuration files. This operation is needed in case the git operations throw up strange errors. Reinitializes the Git object entirely.", category = "", isAllowOverride = false, aspects = {
			"isAsync:false" })
	@ThingworxServiceResult(name = "Result", description = "", baseType = "NOTHING", aspects = {})
	public void DeleteLocalRepoContent()
			throws IllegalStateException, GitAPIException, IOException, InterruptedException, Throwable {
		_logger.warn("DeleteLocalRepoContent:1 for entity: " + this.getName());
		Thread.sleep(5);
		FileRepositoryThing srcRepo = (FileRepositoryThing) EntityUtilities.findEntity(str_FileRepository,
				ThingworxRelationshipTypes.Thing);
		try {
			String str_FolderPath = srcRepo.getRootPath();
			closeGit();
			_logger.warn("DeleteLocalRepoContent:2, starting to delete files. All files should not be locked;");
			str_FolderPath += str_FileRepoPath;
			// deleteDirectory(new File(str_FolderPath), "DeleteLocalRepoContent");
			deleteGitFolder(new File(str_FolderPath), "DeleteLocalRepoContent");
			Thread.sleep(5);
		} catch (Exception ex) {
			StringWriter errors = new StringWriter();
			ex.printStackTrace(new PrintWriter(errors));
			_logger.error("Error encountered in DeleteLocalRepoContent for entity: " + this.getName()
					+ "; Error Message: " + errors.toString());
		}
		_logger.warn("DeleteLocalRepoContent:3 for entity: " + this.getName());
	}

	@ThingworxServiceDefinition(name = "Checkout", description = "", category = "", isAllowOverride = false, aspects = {
			"isAsync:false" })
	@ThingworxServiceResult(name = "Result", description = "", baseType = "NOTHING", aspects = {})
	public void Checkout(
			@ThingworxServiceParameter(name = "BranchNameOrCommit", description = "Switches the working tree to the specified branch. This is a wrapper on top of checkout <branch>.It does not autocreate new branches.", baseType = "STRING", aspects = {
					"defaultValue:master" }) String BranchNameOrCommit)
			throws Throwable, GitAPIException {
		_logger.trace("Entering Service: Checkout");
		String str_CurrentMethodName = "Checkout";
		Git myGitFolder = getGitObject("Checkout");
		Ref ref;
		try {
			ref = myGitFolder.checkout().setName(BranchNameOrCommit).call();
		} catch (RefNotFoundException ex) {
			_logger.warn(
					"Branch not found; Assuming there is no local branch tracking the remote; Creating a new local tracking branch for "
							+ BranchNameOrCommit + "; This is a normal operation message.");
			ref = myGitFolder.checkout().setCreateBranch(true).setName(BranchNameOrCommit)
					.setUpstreamMode(SetupUpstreamMode.TRACK).setStartPoint("origin/" + BranchNameOrCommit).call();
		}
		bool_isDetachedHead = getGitObject("Checkout").getRepository().getFullBranch().indexOf("refs/heads") != -1
				? false
				: true;
		str_CurrentBranchOrCommit = BranchNameOrCommit;
		String str_LogResult = (ref != null) ? ref.toString() : "No message.";
		LogOperationResult(str_LogResult, str_CurrentMethodName);
		_logger.trace("Exiting Service: Checkout");
	}

	@ThingworxServiceDefinition(name = "GetCurrentBranch", description = "", category = "", isAllowOverride = false, aspects = {
			"isAsync:false" })
	@ThingworxServiceResult(name = "Result", description = "", baseType = "INFOTABLE", aspects = {
			"isEntityDataShape:true", "dataShape:Git.CurrentBranchStatus.DataShape" })
	public InfoTable GetCurrentBranch() {
		try {
			_logger.trace("Entering Service: GetCurrentBranch");
			InfoTable iftbl_CurrentBranchStatus = InfoTableInstanceFactory
					.createInfoTableFromDataShape("Git.CurrentBranchStatus.DataShape");
			ValueCollection vc = new ValueCollection();

			vc.put("BranchName", new StringPrimitive(str_CurrentBranchOrCommit));
			vc.put("DetachedHEAD", new BooleanPrimitive(bool_isDetachedHead));
			iftbl_CurrentBranchStatus.addRow(vc);
			_logger.trace("Exiting Service: GetCurrentBranch");
			return iftbl_CurrentBranchStatus;
		} catch (Exception ex) {
			StringWriter errors = new StringWriter();
			ex.printStackTrace(new PrintWriter(errors));
			_logger.error(errors.toString());
			return new InfoTable();
		}
	}

	@ThingworxServiceDefinition(name = "GetBranchList", description = "", category = "", isAllowOverride = false, aspects = {
			"isAsync:false" })
	@ThingworxServiceResult(name = "Result", description = "", baseType = "INFOTABLE", aspects = {
			"isEntityDataShape:true", "dataShape:Git.BranchList.DataShape" })
	public InfoTable GetBranchList() {
		_logger.trace("Entering Service: GetBranchList");

		try {
			InfoTable iftbl_BranchList = InfoTableInstanceFactory
					.createInfoTableFromDataShape("Git.BranchList.DataShape");
			Git myGit = getGitObject("GetBranchList");
			List<Ref> branches = myGit.branchList().setListMode(ListMode.ALL).call();
			for (Iterator<Ref> iterator = branches.iterator(); iterator.hasNext();) {
				Ref ref = (Ref) iterator.next();
				ValueCollection vc = new ValueCollection();

				String str_LongBranchName = ref.getName();
				String str_ShortBranchName, str_BranchType;
				str_ShortBranchName = (str_LongBranchName != "HEAD")
						? str_LongBranchName.replace("refs/heads/", "").replace("refs/remotes/origin/", "")
						: "HEAD";
				str_BranchType = (str_LongBranchName != "HEAD")
						? (str_LongBranchName.indexOf("refs/heads/") >= 0 ? "LOCAL" : "REMOTE")
						: "HEAD";
				vc.put("BranchName", new StringPrimitive(str_LongBranchName));
				vc.put("ShortBranchName", new StringPrimitive(str_ShortBranchName));
				vc.put("BranchType", new StringPrimitive(str_BranchType));
				iftbl_BranchList.addRow(vc);
			}
			_logger.trace("Exiting Service: GetBranchList");
			return iftbl_BranchList;

		} catch (Exception ex) {
			StringWriter errors = new StringWriter();
			ex.printStackTrace(new PrintWriter(errors));
			_logger.error(errors.toString());
			_logger.trace("Exiting Service: GetBranchList");
			return new InfoTable();
		}

	}

	@ThingworxServiceDefinition(name = "DeleteLocalBranch", description = "This method deletes a local branch. Used in the case a remote branch was deleted/pruned and you want to remove your local copy.", category = "", isAllowOverride = false, aspects = {
			"isAsync:false" })
	@ThingworxServiceResult(name = "Result", description = "", baseType = "STRING", aspects = {})
	public String DeleteLocalBranch(
			@ThingworxServiceParameter(name = "BranchName", description = "Branch name to be deleted, without the refs/heads/ part", baseType = "STRING") String BranchName)
			throws IOException, GitAPIException {
		_logger.trace("Entering Service: DeleteLocalBranch");
		String str_CurrentMethodName = "DeleteLocalBranch";
		try {
			Git myGitFolder = getGitObject("DeleteLocalBranch");

			List<String> lstr = myGitFolder.branchDelete().setForce(true).setBranchNames("refs/heads/" + BranchName)
					.call();
			String str_LogResult = "";
			if (lstr.size() == 0) {
				str_LogResult += " Branch " + BranchName + " was ignored or invalid.";
			}
			for (String str : lstr) {
				str_LogResult += str;
			}

			LogOperationResult(str_LogResult, str_CurrentMethodName);
			return str_LogResult;
		} catch (Exception e) {
			StringWriter errors = new StringWriter();
			e.printStackTrace(new PrintWriter(errors));
			_logger.error(errors.toString());
			try {
				LogOperationResult(errors.toString(), str_CurrentMethodName);
			} catch (Exception e1) {
				// TODO Auto-generated catch block
				e1.printStackTrace();
			}
			return "DeleteLocalBranch Error: " + errors.toString();
		}
	}

	@ThingworxServiceDefinition(name = "GetCommitList", description = "Get a list of the commits for the current branch; if the current index is pointing to a commit, then it will return the commit list for the Initial branch configured in the Config section", category = "", isAllowOverride = false, aspects = {
			"isAsync:false" })
	@ThingworxServiceResult(name = "Result", description = "", baseType = "INFOTABLE", aspects = {
			"isEntityDataShape:true", "dataShape:Git.CommitList.DataShape" })
	public InfoTable GetCommitList() {
		_logger.trace("Entering Service: GetCommitList");
		try {
			Git myGitFolder = getGitObject("GetCommitList");
			InfoTable iftbl_CommitList = InfoTableInstanceFactory
					.createInfoTableFromDataShape(Const.str_CommitListDataShapeName);
			// Git myGit = GetRepository();
			ObjectId obj = myGitFolder.getRepository().resolve("refs/heads/" + str_CurrentBranchOrCommit);
			if (obj == null)
				obj = myGitFolder.getRepository().resolve("refs/heads/"
						+ ((String) getConfigurationSetting(Const.str_ConfTableName, Const.str_InitialBranch)));
			if (obj != null) {
				Iterable<RevCommit> commits = myGitFolder.log().add(obj).call();
				for (Iterator<RevCommit> iterator = commits.iterator(); iterator.hasNext();) {
					RevCommit commit = (RevCommit) iterator.next();
					ValueCollection vc = new ValueCollection();
					vc.put("CommitTime", new DatetimePrimitive(new DateTime(((long) commit.getCommitTime() * 1000))));
					vc.put("CommitName", new StringPrimitive(commit.getShortMessage()));
					vc.put("CommitID", new StringPrimitive(commit.getId().name()));
					iftbl_CommitList.addRow(vc);
				}
			}
			_logger.trace("Exiting Service: GetCommitList");
			return iftbl_CommitList;
		} catch (Exception ex) {
			StringWriter errors = new StringWriter();
			ex.printStackTrace(new PrintWriter(errors));
			_logger.error(errors.toString());
			return new InfoTable();
		}
	}

	@ThingworxServiceDefinition(name = "Status", description = "", category = "", isAllowOverride = false, aspects = {
			"isAsync:false" })
	@ThingworxServiceResult(name = "Result", description = "", baseType = "INFOTABLE", aspects = {
			"isEntityDataShape:true", "dataShape:Git.Status.DataShape" })
	public InfoTable Status() throws Exception {
		_logger.trace("Entering Service: Status");
		InfoTable iftbl_Status = InfoTableInstanceFactory.createInfoTableFromDataShape("Git.Status.DataShape");
		Git myGitObject = getGitObject("Status");
		org.eclipse.jgit.api.Status status = myGitObject.status().call();
		for (String stat : status.getModified()) {
			ValueCollection vc = new ValueCollection();
			vc.put("File", new StringPrimitive(stat));
			vc.put("Status", new StringPrimitive("Modified"));
			iftbl_Status.addRow(vc);
		}
		for (String stat : status.getAdded()) {
			ValueCollection vc = new ValueCollection();
			vc.put("File", new StringPrimitive(stat));
			vc.put("Status", new StringPrimitive("Added"));
			iftbl_Status.addRow(vc);
		}
		for (String stat : status.getChanged()) {
			ValueCollection vc = new ValueCollection();
			vc.put("File", new StringPrimitive(stat));
			vc.put("Status", new StringPrimitive("Changed"));
			iftbl_Status.addRow(vc);
		}
		for (String stat : status.getIgnoredNotInIndex()) {
			ValueCollection vc = new ValueCollection();
			vc.put("File", new StringPrimitive(stat));
			vc.put("Status", new StringPrimitive("Ignored"));
			iftbl_Status.addRow(vc);
		}
		for (String stat : status.getMissing()) {
			ValueCollection vc = new ValueCollection();
			vc.put("File", new StringPrimitive(stat));
			vc.put("Status", new StringPrimitive("Missing"));
			iftbl_Status.addRow(vc);
		}
		for (String stat : status.getRemoved()) {
			ValueCollection vc = new ValueCollection();
			vc.put("File", new StringPrimitive(stat));
			vc.put("Status", new StringPrimitive("Removed"));
			iftbl_Status.addRow(vc);
		}
		// for (String stat:status.getUncommittedChanges())
		// {
		// ValueCollection vc = new ValueCollection();
		// vc.put("File", new StringPrimitive(stat));
		// vc.put("Status", new StringPrimitive("UNC"));
		// iftbl_Status.addRow(vc);
		// }
		for (String stat : status.getUntracked()) {
			ValueCollection vc = new ValueCollection();
			vc.put("File", new StringPrimitive(stat));
			vc.put("Status", new StringPrimitive("Untracked"));
			iftbl_Status.addRow(vc);
		}
		for (String stat : status.getUntrackedFolders()) {
			ValueCollection vc = new ValueCollection();
			vc.put("File", new StringPrimitive(stat));
			vc.put("Status", new StringPrimitive("UntrackedFolder"));
			iftbl_Status.addRow(vc);
		}
		_logger.trace("Exiting Service: Status");
		return iftbl_Status;
	}

	@ThingworxServiceDefinition(name = "GetDiffPerFile", description = "", category = "", isAllowOverride = false, aspects = {
			"isAsync:false" })
	@ThingworxServiceResult(name = "Result", description = "", baseType = "STRING", aspects = {})
	public String GetDiffPerFile(
			@ThingworxServiceParameter(name = "File", description = "", baseType = "STRING") String File)
			throws Exception, GitAPIException {
		_logger.trace("Entering Service: GetDiffPerFile");
		if (File != null) {
			Git myGitObject = getGitObject("GetDiffPerFile");
			ByteArrayOutputStream dif = new ByteArrayOutputStream();
			myGitObject.diff().setPathFilter(PathFilter.create(File)).setOutputStream(dif).call();
			_logger.trace("Exiting Service: GetDiffPerFile");
			return dif.toString("UTF-8");
		} else
			return "";
	}

	@ThingworxServiceDefinition(name = "GetDiffPerFileBetweenCommits", description = "", category = "", isAllowOverride = false, aspects = {
			"isAsync:false" })
	@ThingworxServiceResult(name = "Result", description = "", baseType = "STRING", aspects = {})
	public String GetDiffPerFileBetweenCommits(
			@ThingworxServiceParameter(name = "File", description = "", baseType = "STRING") String File,
			@ThingworxServiceParameter(name = "FromCommitID", description = "", baseType = "STRING") String str_FromCommitID)
			throws Exception, GitAPIException {
		_logger.trace("Entering Service: GetDiffPerFileBetweenCommits");
		if (File != null) {
			try {
				String str_ToCommitID = "";
				Git myGitObject = getGitObject("GetDiffPerFileBetweenCommits");
				Repository repo = myGitObject.getRepository();
				ObjectId commit = repo.resolve(str_FromCommitID);
				RevWalk walk = new RevWalk(repo);
				RevCommit toCommit = walk.parseCommit(commit);
				if (toCommit.getParentCount() > 0) {
					str_ToCommitID = toCommit.getParent(0).getName();
				}
				AbstractTreeIterator newTreeParser = prepareTreeParser(repo, str_FromCommitID);
				ByteArrayOutputStream dif = new ByteArrayOutputStream();
				if (toCommit.getParentCount() == 0) {
					myGitObject.diff().setNewTree(newTreeParser).setOldTree(new EmptyTreeIterator())
							.setPathFilter(PathFilter.create(File)).setOutputStream(dif).call();
				} else {
					AbstractTreeIterator oldTreeParser = prepareTreeParser(repo, str_ToCommitID);
					myGitObject.diff().setNewTree(newTreeParser).setOldTree(oldTreeParser)
							.setPathFilter(PathFilter.create(File)).setOutputStream(dif).call();
				}
				walk.close();
				_logger.trace("Exiting Service: GetDiffPerFileBetweenCommits");
				String str_DiffResult = dif.toString("UTF-8");
				// anything larger than the configured max diff size will not be sent to the
				// browser at all.
				return str_DiffResult.length() > this.GetIntegerPropertyValue(Const.str_MaxDiffSize)
						? "Diff size is too big to be displayed!"
						: str_DiffResult;
			} catch (Exception ex) {
				StringWriter errors = new StringWriter();
				ex.printStackTrace(new PrintWriter(errors));
				_logger.error(errors.toString());
				return "";
			}
		} else
			return "";
	}

	@ThingworxServiceDefinition(name = "GetCommitInfo", description = "This service gets a commit information based on the Commit ID", category = "", isAllowOverride = false, aspects = {
			"isAsync:false" })
	@ThingworxServiceResult(name = "Result", description = "", baseType = "INFOTABLE", aspects = {
			"isEntityDataShape:true", "dataShape:GitBackup.CommitInfo.DataShape" })
	public InfoTable GetCommitInfo(
			@ThingworxServiceParameter(name = "CommitID", description = "", baseType = "STRING") String CommitID)
			throws Exception {
		_logger.trace("Entering Service: GetCommitInfo");
		InfoTable iftbl_Status = InfoTableInstanceFactory
				.createInfoTableFromDataShape(Const.str_CommitInfoDataShapeName);
		try {
			if (CommitID != null) {

				Repository myGitRepository = getGitObject("GetCommitInfo").getRepository();
				ObjectId commit = myGitRepository.resolve(CommitID);
				try (RevWalk walk = new RevWalk(myGitRepository)) {
					RevCommit commitAgain = walk.parseCommit(commit);
					ValueCollection vc = new ValueCollection();
					vc.put("CommitID", new StringPrimitive(commitAgain.getId().name()));
					vc.put("Parents", new StringPrimitive(ProcessRevCommit(commitAgain.getParents())));
					vc.put("Author", new StringPrimitive(commitAgain.getAuthorIdent().getName() + " "
							+ commitAgain.getAuthorIdent().getEmailAddress()));
					vc.put("Date", new DatetimePrimitive(new DateTime(((long) commitAgain.getCommitTime() * 1000))));
					vc.put("Commiter", new StringPrimitive(commitAgain.getCommitterIdent().getName() + " "
							+ commitAgain.getCommitterIdent().getEmailAddress()));
					vc.put("CommitDescription", new StringPrimitive(commitAgain.getFullMessage()));
					InfoTable iftbl_CommitChangedFiles = InfoTableInstanceFactory
							.createInfoTableFromDataShape(Const.str_CommitChangedFiles);
					DiffFormatter diffFormatter = new DiffFormatter(DisabledOutputStream.INSTANCE);
					diffFormatter.setRepository(myGitRepository);
					List<DiffEntry> entries;
					if (commitAgain.getParentCount() != 0)
						entries = diffFormatter.scan(commitAgain.getParent(0), commitAgain.getTree());
					else
						entries = diffFormatter.scan(null, commitAgain.getTree());

					for (DiffEntry diffEntry : entries) {
						ValueCollection v2 = new ValueCollection();

						switch (diffEntry.getChangeType()) {
						case ADD: {
							v2.put("FileName", new StringPrimitive(diffEntry.getNewPath()));
							break;
						}
						case DELETE: {
							v2.put("FileName", new StringPrimitive(diffEntry.getOldPath()));
							break;
						}
						case MODIFY: {
							v2.put("FileName", new StringPrimitive(diffEntry.getNewPath()));
							break;
						}
						case COPY: {
							v2.put("FileName", new StringPrimitive(diffEntry.getNewPath()));
							break;
						}
						case RENAME: {
							v2.put("FileName", new StringPrimitive(diffEntry.getNewPath()));
							break;
						}
						}
						v2.put("Status", new StringPrimitive(diffEntry.getChangeType().toString()));
						iftbl_CommitChangedFiles.addRow(v2);

					}
					diffFormatter.close();

					vc.put("ChangedFiles", new InfoTablePrimitive(iftbl_CommitChangedFiles));
					iftbl_Status.addRow(vc);
					walk.dispose();
					_logger.trace("Exiting Service: GetCommitInfo");

				}
			} else {
				_logger.trace("No Commit ID provided to the GetCommitInfo");

			}
			return iftbl_Status;
		} catch (Exception ex) {
			StringWriter errors = new StringWriter();
			ex.printStackTrace(new PrintWriter(errors));
			_logger.error(errors.toString());
			return iftbl_Status;
		}

	}

	private String ProcessRevCommit(RevCommit[] parents) {

		if (parents == null) {
			return "";
		}
		String str_Parents = "";
		for (int x = 0; x < parents.length; x++) {
			str_Parents += parents[x].getName();
		}
		return str_Parents;
	}

	private void LogOperationResult(String str_OperationResult, String str_ServiceName) throws Exception {
		Thing rsc = (Thing) EntityUtilities.findEntity(Const.str_UtilityThingName, ThingworxRelationshipTypes.Thing);
		ValueCollection vc = new ValueCollection();
		vc.put("timestamp", new DatetimePrimitive(new DateTime(System.currentTimeMillis())));
		vc.put("User", new StringPrimitive(GetCurrentUser()));
		vc.put("ServiceName", new StringPrimitive(str_ServiceName));
		vc.put("Content", new StringPrimitive(str_OperationResult));
		vc.put("Source", new StringPrimitive(this.getName()));
		rsc.processServiceRequest("AddLogEntry", vc);

	}

	private String GetCurrentUser() {
		return ThreadLocalContext.getSecurityContext().getName();
	}

	private void closeGit() throws InterruptedException, NoSuchMethodException, SecurityException,
			IllegalAccessException, IllegalArgumentException, InvocationTargetException, IOException, GitAPIException {
		Git gitObjectToClose = getGitObject("closeGit");
		FileRepository gitRepository = (FileRepository) gitObjectToClose.getRepository();
		Thread.sleep(200);
		_logger.warn("Method DeleteLocalRepoContent, performing 1st myGitRepository.close()");
		gitRepository.close();
		Thread.sleep(200);
		// _logger.warn("Method DeleteLocalRepoContent, performing 2nd
		// myGitRepository.close()");
//		myGitRepository.close();
//		_logger.warn("Method DeleteLocalRepoContent, performing 3rd myGitRepository.close()");
//		myGitRepository.close();
//		Thread.sleep(200);
		RepositoryCache.clear();
		_logger.warn("Method DeleteLocalRepoContent, performing myGitObject.close()");
		gitObjectToClose.close();
		WindowCacheConfig wconfig = new WindowCacheConfig();
		wconfig.setPackedGitMMAP(false);
		WindowCache.reconfigure(wconfig);
		gitObject = null;
	}

	private void getFilesToDelete(File path, String str_Source, ArrayList<File> lst_FilesToDelete,
			ArrayList<File> lst_FoldersToDelete) {
		// _logger.warn("Starting to remove files from directory "+path.toString());
		if (path.exists()) {
			File[] files = path.listFiles();
			for (int i = 0; i < files.length; i++) {
				if (files[i].isDirectory()) {
					lst_FoldersToDelete.add(files[i]);
					getFilesToDelete(files[i], str_Source, lst_FilesToDelete, lst_FoldersToDelete);
				} else {
					lst_FilesToDelete.add(files[i]);
					
				}
			}
		}

	}

	private void deleteGitFolder(File path, String str_Source) throws InterruptedException {
		ArrayList<File> lst_FilesToDelete = new ArrayList<File>();
		ArrayList<File> lst_FoldersToDelete = new ArrayList<File>();
		getFilesToDelete(path, str_Source, lst_FilesToDelete, lst_FoldersToDelete);
		_logger.warn("Folders to delete size: " + lst_FoldersToDelete.size());
		_logger.warn("Files to delete size: " + lst_FilesToDelete.size());
		ArrayList<File> lst_DeletedFiles = new ArrayList<File>();
		ArrayList<File> lst_DeletedFolders = new ArrayList<File>();
		for (File file : lst_FilesToDelete) {
			Thread.sleep(1);
			try {
				Files.delete(file.toPath());
				lst_DeletedFiles.add(file);
			} catch (IOException ex) {
				StringWriter errors = new StringWriter();
				ex.printStackTrace(new PrintWriter(errors));
				_logger.error("Source method: " + str_Source + "; Error removing file " + file.getAbsolutePath()
						+ "; Error: " + ex.toString() + "; Attempting to remove read-only flag.");
				try {
					file.setWritable(true);
					Files.delete(file.toPath());
					lst_DeletedFiles.add(file);
					_logger.warn("Source method: " + str_Source
							+ "; File deleted successfully after read-only was removed " + file.getAbsolutePath());
				} catch (IOException ex2) {
					StringWriter errors2 = new StringWriter();
					ex.printStackTrace(new PrintWriter(errors2));
					_logger.error("Source method: " + str_Source + "; Error removing read-only flag for file "
							+ file.getAbsolutePath() + "; Error: " + ex.toString());
				}
			}
		}
		lst_FilesToDelete.removeAll(lst_DeletedFiles);
		_logger.warn("Files to delete size after removal (should be zero): " + lst_FilesToDelete.size());
		lst_FoldersToDelete.sort((f1, f2) -> Integer.compare(f2.toPath().getNameCount(), f1.toPath().getNameCount()));
		for (File file : lst_FoldersToDelete) {
			try {
				Files.delete(file.toPath());
				lst_DeletedFolders.add(file);
			} catch (IOException ex) {
				StringWriter errors = new StringWriter();
				ex.printStackTrace(new PrintWriter(errors));
				_logger.error("Source method: " + str_Source + "; Error removing folder " + file.getAbsolutePath()
						+ "; Error: " + ex.toString());
			}
		}
		//Removes the final subfolder from the ThingWorx File Repository
		path.delete();

	}

	private Git openOrCreate(File gitDirectory) throws IOException, GitAPIException, InterruptedException {
		Git myGitObject;
		FileRepositoryBuilder repositoryBuilder = new FileRepositoryBuilder();
		repositoryBuilder.addCeilingDirectory(gitDirectory);
		repositoryBuilder.findGitDir(gitDirectory);
		if (repositoryBuilder.getGitDir() == null) {
			myGitObject = Git.init().setDirectory(gitDirectory).setInitialBranch(str_CurrentBranchOrCommit).call();
			// Wait 2 seconds in case any antivirus locks the git repository files
			Thread.sleep(2000);

		} else {
			myGitObject = new Git(repositoryBuilder.build());
		}
		return myGitObject;
	}

	private Git getGitObject(String str_CallerMethod)
			throws IOException, GitAPIException, InterruptedException, NoSuchMethodException, SecurityException,
			IllegalAccessException, IllegalArgumentException, InvocationTargetException {
		// modified so that it will store the Git repository object (from JGIT) in an
		// internal private field, not requiring reopen each time.
		_logger.warn("Retrieving GitBackup Thing : " + this.getName() + "; Object is: " + (gitObject != null)
				+ "; Method: " + str_CallerMethod);
		if (gitObject == null) {
			FileRepositoryThing srcRepo = (FileRepositoryThing) EntityUtilities.findEntity(str_FileRepository,
					ThingworxRelationshipTypes.Thing);
			String str_FolderPath = srcRepo.getRootPath();
			str_FolderPath += str_FileRepoPath;
			Git myGitObject = openOrCreate(new File(str_FolderPath));
			StoredConfig config = myGitObject.getRepository().getConfig();
			config.setString("remote", "origin", "url", str_GitRepoURL);
			config.setString("remote", "origin", "fetch", "+refs/heads/*:refs/remotes/origin/*");
			config.setString("remote", "origin", "prune", "true");
			config.setString("core", null, "autocrlf", "input");
			// prevents Bitmap creation during GarbageCollection
			config.setBoolean("repack", null, "writeBitmaps", false);
			config.save();
			_logger.warn("GitBackup Thing: " + this.getName()
					+ " Git object found null, initialized. Git object created and stored internally for future use. "
					+ "; Method: " + str_CallerMethod);
			gitObject = myGitObject;
		}
		return gitObject;
	}

	private static AbstractTreeIterator prepareTreeParser(Repository repository, String objectId) throws IOException {
		try (RevWalk walk = new RevWalk(repository)) {
			RevCommit commit = walk.parseCommit(ObjectId.fromString(objectId));
			RevTree tree = walk.parseTree(commit.getTree().getId());
			CanonicalTreeParser treeParser = new CanonicalTreeParser();
			try (ObjectReader reader = repository.newObjectReader()) {
				treeParser.reset(reader, tree.getId());
			}
			walk.dispose();
			return treeParser;
		}
	}

	private ValueCollection getGitRepoRemoteCredential(User us_currentUser) throws Exception {
		var propValue = us_currentUser.getPropertyValue(Const.str_GitCredentials);
		if (propValue == null) {
			return new ValueCollection();
		}
		InfoTable iftbl_CredentialStore = ((InfoTablePrimitive) propValue).getValue();
		if (iftbl_CredentialStore == null) {
			return new ValueCollection();
		}
		ValueCollection vc = new ValueCollection();
		vc.put("GitThing", new StringPrimitive(this.getName()));
		ValueCollection result = iftbl_CredentialStore.find(vc);
		return result != null ? result : new ValueCollection();

	}

}
