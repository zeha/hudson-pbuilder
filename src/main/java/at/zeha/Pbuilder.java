package at.zeha;
import hudson.EnvVars;
import hudson.FilePath;
import hudson.Launcher;
import hudson.Extension;
import hudson.Util;
import hudson.util.ArgumentListBuilder;
import hudson.util.FormValidation;
import hudson.model.AbstractBuild;
import hudson.model.BuildListener;
import hudson.model.AbstractProject;
import hudson.model.Result;
import hudson.tasks.Builder;
import hudson.tasks.BuildStepDescriptor;
import net.sf.json.JSONObject;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.StaplerRequest;
import org.kohsuke.stapler.QueryParameter;

import javax.servlet.ServletException;

import java.io.FilenameFilter;
import java.io.IOException;
import java.io.File;

/**
 * Debian pbuilder {@link Builder}.
 *
 * <p>
 * When the user configures the project and enables this builder,
 * {@link DescriptorImpl#newInstance(StaplerRequest)} is invoked
 * and a new {@link Pbuilder} is created. The created
 * instance is persisted to the project configuration XML by using
 * XStream, so this allows you to use instance fields (like {@link #name})
 * to remember the configuration.
 *
 * <p>
 * When a build is performed, the {@link #perform(Build, Launcher, BuildListener)} method
 * will be invoked. 
 *
 * @author Christian Hofstaedtler
 */
public class Pbuilder extends Builder {

    private final String mirror;
    private final String distribution;
    private final String outputDir;

    @DataBoundConstructor
    public Pbuilder(String mirror, String distribution, String outputDir) {
        this.mirror = mirror;
        this.distribution = distribution;
        this.outputDir = outputDir;
    }

    /**
     * We'll use this from the <tt>config.jelly</tt>.
     * 
     * FIXME: doing defaults in the isEmpty case is probably not the best way to do them.
     */
    public String getMirror() {
    	if (mirror.isEmpty())
    		return "http://http.us.debian.org/debian";
		return mirror;
	}
	
	public String getDistribution() {
		if (distribution.isEmpty())
			return "unstable";
		return distribution;
	}
	
	public String getOutputDir() {
		if (outputDir.isEmpty())
			return "hudson-output";
		return outputDir;
	}

    @Override
    public boolean perform(AbstractBuild build, Launcher launcher, BuildListener listener) throws InterruptedException {
    	ArgumentListBuilder args = new ArgumentListBuilder();
    	File artifactsDir = build.getArtifactsDir();
    	FilePath workspace = build.getWorkspace();
    	int rc;
    	
    	try {
        	EnvVars env = build.getEnvironment(listener);
        	env.override("DEBIAN_FRONTEND", "noninteractive");

        	/* Install pbuilder if it ain't there */
			args.clear();
			args.add("sudo");
			args.add("apt-get");
			args.add("install");
			args.add("-y");
			args.add("build-essential");
			args.add("devscripts");
			args.add("pbuilder");
			listener.getLogger().printf("[pbuilder] $ %s\n", args.toStringWithQuote());
			rc = launcher.launch().cmds(args).envs(env).stdout(listener).pwd(workspace).join();
			if (rc != 0)
				throw new CommandFailedException(rc);
			
			/* prepare (= wipe + create) our output directory */
			hudson.FilePath outputDir = workspace.child(this.outputDir);
			listener.getLogger().printf("[pbuilder] Cleaning outputDir \"%s\"\n", outputDir);
			if (outputDir.exists())
				outputDir.deleteRecursive();
			outputDir.mkdirs();
			
			/* create package source (dsc + diff.gz + tar.gz) */
			args.clear();
			args.add("dpkg-source");
			args.add("-b");
			args.add("-I" + outputDir.getName());
			args.add("-ICVS"); // FIXME: make this configurable
			args.add("-I.svn");
			args.add("-I.git");
			args.add(workspace);
			listener.getLogger().printf("[pbuilder] $ %s\n", args.toStringWithQuote());
			rc = launcher.launch().cmds(args).envs(env).stdout(listener).pwd(outputDir).join();
			if (rc != 0)
				throw new CommandFailedException(rc);

			/* prepare the pbuilder build root ("base.tgz") */
			hudson.FilePath pbuilderBaseTgz = outputDir.child(String.format("pbuilder-base-%s.tgz", distribution));
			if (true) {
				// in future versions we could reuse an old base.tgz from a previous build
				// for now just recreate it everytime
				args.clear();
				args.add("sudo");
				args.add("pbuilder");
				args.add("--create");
				args.add("--basetgz");
				args.add(pbuilderBaseTgz);
				args.add("--mirror");
				args.add(mirror);
				args.add("--distribution");
				args.add(distribution);
				listener.getLogger().printf("[pbuilder] $ %s\n", args.toStringWithQuote());
				rc = launcher.launch().cmds(args).envs(env).stdout(listener).pwd(workspace).join();
				if (rc != 0)
					throw new CommandFailedException(rc);
			}

			/* do the actual build */
			build.getArtifactsDir().mkdir();
			FilePath[] dscs = outputDir.list("*.dsc");
			for (FilePath dsc: dscs) {
				args.clear();
				args.add("sudo");
				args.add("pbuilder");
				args.add("--build");
				args.add("--basetgz");
				args.add(pbuilderBaseTgz);
				args.add("--buildresult");
				args.add(outputDir);
				args.add(dsc);
				listener.getLogger().printf("[pbuilder] $ %s\n", args.toStringWithQuote());
				rc = launcher.launch().cmds(args).envs(env).stdout(listener).pwd(workspace).join();
				if (rc != 0)
					throw new CommandFailedException(rc);
			}
			
			/* show a list of outputs */
			FilePath[] files = outputDir.list("*");
			for (FilePath f: files) {
				listener.getLogger().printf("[pbuilder] output: %s\n", f.getName());
			}
			
		} catch (IOException e) {
			Util.displayIOException(e,listener);
			e.printStackTrace(listener.error("I/O Exception"));
			build.setResult(Result.FAILURE);
			return true;
		} catch (CommandFailedException e) {
			/* a command return non-zero, abort the build */
			e.printStackTrace(listener.error("Command failed Exception"));
			build.setResult(Result.FAILURE);
			return true;
		}
		
        return true;
    }

    private class CommandFailedException extends Exception {

		private final int rc;

		public CommandFailedException(int rc) {
			super(String.format("command failed with rc=%d", rc));
			this.rc = rc;
		}

		private static final long serialVersionUID = -8387577273728341981L;
		
		@SuppressWarnings("unused")
		public int getRc() {
			return this.rc;
		}
    	
    }

    // overrided for better type safety.
    // if your plugin doesn't really define any property on Descriptor,
    // you don't have to do this.
    @Override
    public DescriptorImpl getDescriptor() {
        return (DescriptorImpl)super.getDescriptor();
    }
    
    /**
     * Descriptor for {@link Pbuilder}. Used as a singleton.
     * The class is marked as public so that it can be accessed from views.
     *
     * <p>
     * See <tt>views/hudson/plugins/hello_world/HelloWorldBuilder/*.jelly</tt>
     * for the actual HTML fragment for the configuration screen.
     */
    @Extension // this marker indicates Hudson that this is an implementation of an extension point.
    public static final class DescriptorImpl extends BuildStepDescriptor<Builder> {
        /**
         * To persist global configuration information,
         * simply store it in a field and call save().
         *
         * <p>
         * If you don't want fields to be persisted, use <tt>transient</tt>.
         */
        

        /**
         * Performs on-the-fly validation of the form field 'name'.
         *
         * @param value
         *      This parameter receives the value that the user has typed.
         * @return
         *      Indicates the outcome of the validation. This is sent to the browser.
         */
        /*public FormValidation doCheckName(@QueryParameter String value) throws IOException, ServletException {
            if(value.length()==0)
                return FormValidation.error("Please set a name");
            if(value.length()<4)
                return FormValidation.warning("Isn't the name too short?");
            return FormValidation.ok();
        }*/

        public boolean isApplicable(Class<? extends AbstractProject> aClass) {
            // indicates that this builder can be used with all kinds of project types
            return true;
        }

        /**
         * This human readable name is used in the configuration screen.
         */
        public String getDisplayName() {
            return "pbuilder (Debian package builder)";
        }
    }
}

