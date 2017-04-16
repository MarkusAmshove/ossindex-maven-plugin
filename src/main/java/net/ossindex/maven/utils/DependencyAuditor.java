/**
 *	Copyright (c) 2015-2017 Vör Security Inc.
 *	All rights reserved.
 *	
 *	Redistribution and use in source and binary forms, with or without
 *	modification, are permitted provided that the following conditions are met:
 *	    * Redistributions of source code must retain the above copyright
 *	      notice, this list of conditions and the following disclaimer.
 *	    * Redistributions in binary form must reproduce the above copyright
 *	      notice, this list of conditions and the following disclaimer in the
 *	      documentation and/or other materials provided with the distribution.
 *	    * Neither the name of the <organization> nor the
 *	      names of its contributors may be used to endorse or promote products
 *	      derived from this software without specific prior written permission.
 *	
 *	THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS" AND
 *	ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED
 *	WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE
 *	DISCLAIMED. IN NO EVENT SHALL <COPYRIGHT HOLDER> BE LIABLE FOR ANY
 *	DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES
 *	(INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES;
 *	LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND
 *	ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT
 *	(INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS
 *	SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */
package net.ossindex.maven.utils;

import java.io.IOException;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.eclipse.aether.RepositorySystem;
import org.eclipse.aether.RepositorySystemSession;
import org.eclipse.aether.artifact.Artifact;
import org.eclipse.aether.artifact.DefaultArtifact;
import org.eclipse.aether.collection.CollectRequest;
import org.eclipse.aether.collection.DependencyCollectionException;
import org.eclipse.aether.graph.Dependency;
import org.eclipse.aether.graph.DependencyNode;
import org.eclipse.aether.resolution.DependencyRequest;
import org.eclipse.aether.resolution.DependencyResolutionException;
import org.eclipse.aether.util.graph.visitor.PreorderNodeListGenerator;

import net.ossindex.common.IPackageRequest;
import net.ossindex.common.OssIndexApi;
import net.ossindex.common.PackageDescriptor;

/** Utility code that performs the Maven dependency auditing. Written in a manner
 * that will allow it to be used within Maven plugins as well as outside.
 * 
 * This gathers the transitive dependencies and remembers them, and at the same time
 * assembled a request that is run against OSS Index.
 * 
 * @author Ken Duck
 *
 */
public class DependencyAuditor
{
	private Map<PackageDescriptor,PackageDescriptor> parents = new HashMap<PackageDescriptor,PackageDescriptor>();
	private IPackageRequest request = OssIndexApi.createPackageRequest();

	private RepositorySystem repoSystem;
	private RepositorySystemSession session;

	/**
	 * Testing constructor only
	 */
	DependencyAuditor()
	{
	}

	/** Make a new dependency auditor
	 * 
	 * @param repoSystem Maven repository system
	 * @param session Maven repository system session
	 */
	public DependencyAuditor(RepositorySystem repoSystem, RepositorySystemSession session)
	{
		this.repoSystem = repoSystem;
		this.session = session;
	}

	/**
	 * Add an artifact and its dependencies to the request
	 * @param exclusionSet 
	 */
	public void add(String groupId, String artifactId, String version, Set<String> exclusionSet)
	{
		PackageDescriptor parent = request.add("maven", groupId, artifactId, version);
		parents.put(parent, null);
		addPackageDependencies(parent, groupId, artifactId, version, exclusionSet);
	}


	/** Find all of the dependencies for a specified artifact
	 * @param parent 
	 * 
	 * @param groupId Artifact group ID
	 * @param artifactId Artifact OD
	 * @param version Version number
	 * @param exclusionSet 
	 * @return List of package dependencies
	 */
	private List<PackageDescriptor> addPackageDependencies(PackageDescriptor parent, String groupId, String artifactId, String version, Set<String> exclusionSet)
	{
		List<PackageDescriptor> packageDependency = new LinkedList<PackageDescriptor>();
		String aid = groupId + ":" + artifactId + ":";
		if(version != null) aid += version;
		Dependency dependency = new Dependency( new DefaultArtifact( aid ), "compile" );

		CollectRequest collectRequest = new CollectRequest();
		collectRequest.setRoot( dependency );

		try
		{
			DependencyNode node = repoSystem.collectDependencies( session, collectRequest ).getRoot();

			DependencyRequest dependencyRequest = new DependencyRequest();
			dependencyRequest.setRoot( node );

			repoSystem.resolveDependencies( session, dependencyRequest  );

			PreorderNodeListGenerator nlg = new PreorderNodeListGenerator();
			node.accept( nlg );

			List<Artifact> artifacts = nlg.getArtifacts(false);
			for (Artifact artifact : artifacts)
			{
				String id = artifact.getGroupId() + ":" + artifact.getArtifactId();
				if (!exclusionSet.contains(id)) {
					PackageDescriptor pkgDep = new PackageDescriptor("maven", artifact.getGroupId(), artifact.getArtifactId(), artifact.getVersion());
					// Only include each package once. They might be transitive dependencies from multiple places.
					if (!parents.containsKey(pkgDep)) {
						pkgDep = request.add("maven", artifact.getGroupId(), artifact.getArtifactId(), artifact.getVersion());
						//					MavenPackageDescriptor pkg = new MavenPackageDescriptor(pkgDep);
						parents.put(pkgDep, parent);
						packageDependency.add(pkgDep);
					}
				}
			}
		}
		catch(DependencyCollectionException | DependencyResolutionException e)
		{
			// Ignore so we don't pollute Maven
			//e.printStackTrace();
		}
		return packageDependency;
	}

	/**
	 * Run the audit and wrap the results in MavenPackageDescriptor objects
	 * @return The results collection
	 * @throws IOException On error
	 */
	public Collection<MavenPackageDescriptor> run() throws IOException {
		List<MavenPackageDescriptor> results = new LinkedList<MavenPackageDescriptor>();
		Collection<PackageDescriptor> packages = request.run();
		for (PackageDescriptor pkg : packages) {
			MavenPackageDescriptor mvnPkg = new MavenPackageDescriptor(pkg);
			if (parents.containsKey(pkg)) {
				PackageDescriptor parent = parents.get(pkg);
				if (parent != null) {
					mvnPkg.setParent(new MavenIdWrapper(parent));
				}
			}
			results.add(mvnPkg);
		}
		return results;
	}

	/**
	 * Close the cache, required for clean running
	 */
	public void close()
	{
	}
}
