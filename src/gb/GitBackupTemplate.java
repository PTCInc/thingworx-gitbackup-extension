package gb;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.Proxy.Type;
import java.net.ProxySelector;
import java.net.SocketAddress;
import java.net.URI;
import java.util.Arrays;
import java.util.Date;
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

import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.ObjectReader;
import org.eclipse.jgit.lib.Ref;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.lib.StoredConfig;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevTree;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.storage.file.FileRepositoryBuilder;
import org.eclipse.jgit.transport.CredentialsProvider;
import org.eclipse.jgit.transport.PushResult;
import org.eclipse.jgit.transport.UsernamePasswordCredentialsProvider;
import org.eclipse.jgit.treewalk.AbstractTreeIterator;
import org.eclipse.jgit.treewalk.CanonicalTreeParser;
import org.eclipse.jgit.treewalk.EmptyTreeIterator;

import org.eclipse.jgit.treewalk.filter.PathFilter;

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
import com.thingworx.resources.Resource;
import com.thingworx.resources.entities.EntityServices;
import com.thingworx.security.context.SecurityContext;
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
	private static final long serialVersionUID = -6500080561143490845L;

	// Complete git path will be calculated by concatenating the SCR absolute
	// path and the relative path
	private String str_GitRepoURL, str_FileRepository, str_FileRepoPath, str_CurrentBranchOrCommit, str_ProxyURL;
	private Integer int_ProxyPort;
	private boolean bool_isDetachedHead = false, bool_UseProxy;

	private static Logger _logger = LogUtilities.getInstance().getApplicationLogger(GitBackupTemplate.class);

	public GitBackupTemplate() {
	}

	@Override
	protected void stopThing(ContextType ctx) throws Exception {
		if (!str_GitRepoURL.equals(Const.str_GitRepoURLDefaultValue)) {
			Git Mygit = GetRepository();
			Mygit.getRepository().close();
			Mygit.close();
		}
		super.stopThing(null);
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

		super.initializeThing(null);

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
		// prevents creation of disk folder with default values from configuration table
		// at the first initialization
		if (!str_GitRepoURL.equals(Const.str_GitRepoURLDefaultValue)) {
			Git Mygit = GetRepository();
			String str_Branch = Mygit.getRepository().getFullBranch();
			Mygit.close();
			Mygit.getRepository().close();
			bool_isDetachedHead = (str_Branch.indexOf("refs/heads") != -1 || str_Branch != null) ? false : true;
			str_CurrentBranchOrCommit = (str_Branch != null) ? Mygit.getRepository().getBranch() : Const.str_InitialBranch;//to fix
		}
		if (!this.implementsShape(Const.str_UtilityThingShapeName)) {
			EntityServices es = new EntityServices();
			es.AddShapeToThing(this.getName(), Const.str_UtilityThingShapeName);
		}

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
			long startTimePush = System.nanoTime();
			Git myGitFolder = GetRepository();
			long endTimeOpenRepository = System.nanoTime();
			double durationTimeOpenRepository = (double)(endTimeOpenRepository-startTimePush) / (double)1000000000;
			// 2. Detect if the current ThingWorx user activated the UseGitCommitUserValues
			// checkbox. In this case use for commit the User-level Committer Name and Email
			// instead the global Thing-level ones
			User us_currentUser = UserUtilities.findUser(UserUtilities.getCurrentUser());
			ValueCollection vc_RepoCredentials = getGitRepoRemoteCredential(us_currentUser); 
			String str_User = ((StringPrimitive) vc_RepoCredentials.getPrimitive(Const.str_GitCommitterUser)).getValue();
			String str_Password =  ((PasswordPrimitive) vc_RepoCredentials.getPrimitive(Const.str_GitCommitterPassword)).getValue();
			String str_CommitterName =  ((StringPrimitive) vc_RepoCredentials.getPrimitive(Const.str_GitCommitterName)).getValue();
			String str_CommitterEmail =  ((StringPrimitive) vc_RepoCredentials.getPrimitive(Const.str_GitCommitterEmail)).getValue();
//			boolean isUserExtensionsUsed = ((BooleanPrimitive) (us_currentUser)
//					.getPropertyValue("UseGitCommitUserValues")).getValue();

			// 2. Create the commit
			// 2.1. We add all the modified files to the commit
			myGitFolder.add().addFilepattern(".").call();
			long endTimeAddFiles = System.nanoTime();
			double durationTimeAddFiles = (double)(endTimeAddFiles -endTimeOpenRepository) / (double)1000000000;
			myGitFolder.add().addFilepattern(".").setUpdate(true).call();
			long endTimeAddAllFilesWithSetUpdate = System.nanoTime();
			double durationTimeAddAllFilesWithSetUpdate = (double)(endTimeAddAllFilesWithSetUpdate -endTimeAddFiles) / (double)1000000000;
			// 2.2 We submit the commit to the repository
			myGitFolder.commit().setAll(true).setMessage(Message).setCommitter(str_CommitterName, str_CommitterEmail).call();
			long endTimeCommitToLocalRepository = System.nanoTime();
			double durationTimeCommitToLocalRepository = (double)(endTimeCommitToLocalRepository -endTimeAddAllFilesWithSetUpdate) / (double)1000000000;
			// 3. We will push the commit to the remote repository
			// 3.1. Create the credentials that are needed to authenticate to the online Git
			// repository provider
			CredentialsProvider credentialsProvider = new UsernamePasswordCredentialsProvider(str_User, str_Password);
			// 3.2. Push the changes to the online Git repository
			Iterable<PushResult> prList = myGitFolder.push().setRemote("origin")
					.setCredentialsProvider(credentialsProvider).call();
			long endTimePushFinish = System.nanoTime();
			double durationTimePushFinish = (double)(endTimePushFinish -endTimeCommitToLocalRepository) / (double)1000000000;
			// 4. Various close operations to make sure there is no file lock left active on
			// disk. Needs improvement.
			myGitFolder.getRepository().close();
			long endTimeCloseGit = System.nanoTime();
			double durationTimeCloseGitRepository = (double)(endTimeCloseGit -endTimePushFinish) / (double)1000000000;
			myGitFolder.close();
			long endTimeCloseRepository = System.nanoTime();
			double durationTimeCloseGit = (double)(endTimeCloseRepository -endTimeCloseGit) / (double)1000000000;
			 
			_logger.trace("Exiting Service: Push");
			String str_LogResult = "";
			for (PushResult pr : prList) {
				str_LogResult += pr.getRemoteUpdates().toString();
			}
			str_LogResult += 
			" Debug Timings: #1.OpenGit: "+durationTimeOpenRepository+
			"#2.AddFiles: "+durationTimeAddFiles+
			"#3.AddAllDeletedFiles: "+durationTimeAddAllFilesWithSetUpdate+
			"#4.CommitToLocalRepository: "+durationTimeCommitToLocalRepository+
			"#5.Push: "+durationTimePushFinish+
			"#6.CloseRepository: "+durationTimeCloseGitRepository+
			"#7.CloseGit: "+durationTimeCloseGit
			;
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

	@ThingworxServiceDefinition(name = "Pull", description = "Pulls the last commit to the File Repository path", category = "", isAllowOverride = false, aspects = {
			"isAsync:false" })
	@ThingworxServiceResult(name = "Result", description = "", baseType = "STRING", aspects = {})
	public String Pull(
			@ThingworxServiceParameter(name = "Force", description = "Forces a hard reset instead of a normal pull", baseType = "BOOLEAN") Boolean Force)
			throws IOException, GitAPIException {
		_logger.trace("Entering Service: Pull");
		String str_CurrentMethodName = "Pull";
		try {

			Git myGitFolder = GetRepository();
			User us_currentUser = UserUtilities.findUser(UserUtilities.getCurrentUser());
			ValueCollection vc_RepoCredentials = getGitRepoRemoteCredential(us_currentUser); 
			String str_User = ((StringPrimitive) vc_RepoCredentials.getPrimitive(Const.str_GitCommitterUser)).getValue();
			String str_Password =  ((PasswordPrimitive) vc_RepoCredentials.getPrimitive(Const.str_GitCommitterPassword)).getValue();
			
			CredentialsProvider credentialsProvider = new UsernamePasswordCredentialsProvider(str_User, str_Password);
			if (Force != null && Force == true) {
				myGitFolder.reset().setMode(ResetType.HARD).call();
			}
			PullResult pr = myGitFolder.pull().setCredentialsProvider(credentialsProvider).call();
			//trying to force closing open file handles
			myGitFolder.getRepository().close();
			myGitFolder.close();

			_logger.trace("Exiting Service: Pull");
			String str_LogResult = (pr.isSuccessful() == true ? "Successful. " : "Unsuccessful.") + pr.toString();
			LogOperationResult(str_LogResult, str_CurrentMethodName);
			return str_LogResult;
		} catch (Exception e)

		{
			StringWriter errors = new StringWriter();
			e.printStackTrace(new PrintWriter(errors));
			_logger.error(errors.toString());
			try {
				LogOperationResult(errors.toString(), str_CurrentMethodName);
			} catch (Exception e1) {
				// TODO Auto-generated catch block
				e1.printStackTrace();
			}
			return "Pull Error: " + errors.toString();

		}

	}

	@ThingworxServiceDefinition(name = "DeleteLocalRepoContent", description = "Deletes all files from the local repo path including the git configuration files. This operation is needed in case the git operations throw up strange errors.", category = "", isAllowOverride = false, aspects = {
			"isAsync:false" })
	@ThingworxServiceResult(name = "Result", description = "", baseType = "NOTHING", aspects = {})
	public void DeleteLocalRepoContent() throws IllegalStateException, GitAPIException, IOException {
		_logger.trace("Entering Service: ResetLocalRepo");
		FileRepositoryThing srcRepo = (FileRepositoryThing) EntityUtilities.findEntity(str_FileRepository,
				ThingworxRelationshipTypes.Thing);
		Git myGitFolder = GetRepository();
		myGitFolder.getRepository().close();
		myGitFolder.close();
		String str_FolderPath = srcRepo.getRootPath();
		str_FolderPath += str_FileRepoPath;
		deleteDirectory(new File(str_FolderPath));

		_logger.trace("Exiting Service: ResetLocalRepo");
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
		Git myGitFolder = GetRepository();
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
		myGitFolder.getRepository().close();
		myGitFolder.close();
		bool_isDetachedHead = GetRepository().getRepository().getFullBranch().indexOf("refs/heads") != -1 ? false
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
	public InfoTable GetCurrentBranch() throws Exception {
		_logger.trace("Entering Service: GetCurrentBranch");
		InfoTable iftbl_CurrentBranchStatus = InfoTableInstanceFactory
				.createInfoTableFromDataShape("Git.CurrentBranchStatus.DataShape");
		ValueCollection vc = new ValueCollection();

		vc.put("BranchName", new StringPrimitive(str_CurrentBranchOrCommit));
		vc.put("DetachedHEAD", new BooleanPrimitive(bool_isDetachedHead));
		iftbl_CurrentBranchStatus.addRow(vc);
		_logger.trace("Exiting Service: GetCurrentBranch");
		return iftbl_CurrentBranchStatus;
	}

	@ThingworxServiceDefinition(name = "GetBranchList", description = "", category = "", isAllowOverride = false, aspects = {
			"isAsync:false" })
	@ThingworxServiceResult(name = "Result", description = "", baseType = "INFOTABLE", aspects = {
			"isEntityDataShape:true", "dataShape:Git.BranchList.DataShape" })
	public InfoTable GetBranchList() throws Exception {
		_logger.trace("Entering Service: GetBranchList");
		InfoTable iftbl_BranchList = InfoTableInstanceFactory.createInfoTableFromDataShape("Git.BranchList.DataShape");
		Git myGit = GetRepository();
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
		myGit.getRepository().close();
		myGit.close();

		_logger.trace("Exiting Service: GetBranchList");
		return iftbl_BranchList;
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
			Git myGitFolder = GetRepository();

			List<String> lstr = myGitFolder.branchDelete().setForce(true).setBranchNames("refs/heads/" + BranchName)
					.call();
			myGitFolder.getRepository().close();
			myGitFolder.close();
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
	public InfoTable GetCommitList() throws Exception {
		_logger.trace("Entering Service: GetCommitList");
		Git myGitFolder = GetRepository();
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
		myGitFolder.getRepository().close();
		myGitFolder.close();

		_logger.trace("Exiting Service: GetCommitList");
		return iftbl_CommitList;
	}

	@ThingworxServiceDefinition(name = "Status", description = "", category = "", isAllowOverride = false, aspects = {
			"isAsync:false" })
	@ThingworxServiceResult(name = "Result", description = "", baseType = "INFOTABLE", aspects = {
			"isEntityDataShape:true", "dataShape:Git.Status.DataShape" })
	public InfoTable Status() throws Exception {
		_logger.trace("Entering Service: Status");
		InfoTable iftbl_Status = InfoTableInstanceFactory.createInfoTableFromDataShape("Git.Status.DataShape");

		Git myGitFolder = GetRepository();

		org.eclipse.jgit.api.Status status = myGitFolder.status().call();

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

		myGitFolder.getRepository().close();
		myGitFolder.close();
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
			Git myGitFolder = GetRepository();
			ByteArrayOutputStream dif = new ByteArrayOutputStream();
			myGitFolder.diff().setPathFilter(PathFilter.create(File)).setOutputStream(dif).call();
			myGitFolder.getRepository().close();
			myGitFolder.close();
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
				Git myGit = GetRepository();
				Repository repo = GetRepository().getRepository();
				ObjectId commit = repo.resolve(str_FromCommitID);
				RevWalk walk = new RevWalk(repo);
				RevCommit toCommit = walk.parseCommit(commit);

				if (toCommit.getParentCount() > 0) {
					str_ToCommitID = toCommit.getParent(0).getName();
				}
				AbstractTreeIterator newTreeParser = prepareTreeParser(repo, str_FromCommitID);

				ByteArrayOutputStream dif = new ByteArrayOutputStream();
				if (toCommit.getParentCount() == 0) {
					myGit.diff().setNewTree(newTreeParser).setOldTree(new EmptyTreeIterator())
							.setPathFilter(PathFilter.create(File)).setOutputStream(dif).call();
				} else {
					AbstractTreeIterator oldTreeParser = prepareTreeParser(repo, str_ToCommitID);
					myGit.diff().setNewTree(newTreeParser).setOldTree(oldTreeParser)
							.setPathFilter(PathFilter.create(File)).setOutputStream(dif).call();
				}
				walk.close();
				myGit.getRepository().close();
				myGit.close();
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

				Repository repo = GetRepository().getRepository();
				ObjectId commit = repo.resolve(CommitID);
				try (RevWalk walk = new RevWalk(repo)) {
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
					// TreeWalk treeWalk = new TreeWalk(repo);
					// treeWalk.setRecursive(true);
					// treeWalk.addTree(commitAgain.getTree());
					// for (RevCommit parent:commitAgain.getParents())
					// {
					// RevCommit rcom = walk.parseCommit(parent.getId());
					// treeWalk.addTree(rcom.getTree());
					// }
					DiffFormatter diffFormatter = new DiffFormatter(DisabledOutputStream.INSTANCE);
					diffFormatter.setRepository(repo);
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
					repo.close();

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

	private boolean deleteDirectory(File path) {
		if (path.exists()) {
			File[] files = path.listFiles();
			for (int i = 0; i < files.length; i++) {
				if (files[i].isDirectory()) {
					deleteDirectory(files[i]);
				} else {
					files[i].delete();
				}
			}
		}
		return (path.delete());
	}

	private Git openOrCreate(File gitDirectory) throws IOException, GitAPIException {
		Git git;
		FileRepositoryBuilder repositoryBuilder = new FileRepositoryBuilder();
		repositoryBuilder.addCeilingDirectory(gitDirectory);
		repositoryBuilder.findGitDir(gitDirectory);
		if (repositoryBuilder.getGitDir() == null) {
			git = Git.init().setDirectory(gitDirectory).setInitialBranch(str_CurrentBranchOrCommit).call();

		} else {
			git = new Git(repositoryBuilder.build());
		}
		return git;
	}

	private Git GetRepository() throws IOException, GitAPIException {
		FileRepositoryThing srcRepo = (FileRepositoryThing) EntityUtilities.findEntity(str_FileRepository,
				ThingworxRelationshipTypes.Thing);
		String str_FolderPath = srcRepo.getRootPath();
		str_FolderPath += str_FileRepoPath;
		Git myGitFolder = openOrCreate(new File(str_FolderPath));
		StoredConfig config = myGitFolder.getRepository().getConfig();
		config.setString("remote", "origin", "url", str_GitRepoURL);
		config.setString("remote", "origin", "fetch", "+refs/heads/*:refs/remotes/origin/*");
		config.setString("remote", "origin", "prune", "true");
		config.setString("core", null, "autocrlf", "input");
		config.save();
		return myGitFolder;
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
	
	private ValueCollection getGitRepoRemoteCredential(User us_currentUser) throws Exception
	{
		InfoTable iftbl_CredentialStore = ((InfoTablePrimitive) (us_currentUser).getPropertyValue(Const.str_GitCredentials)).getValue();
		ValueCollection vc = new ValueCollection();
		vc.put("GitThing", new StringPrimitive(this.getName()));
		return iftbl_CredentialStore.find(vc);
		
	}

}
