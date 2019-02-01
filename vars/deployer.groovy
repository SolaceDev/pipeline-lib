import static com.salemove.Collections.addWithoutDuplicates
import com.salemove.Deployer
import com.salemove.deploy.Github

def wrapPodTemplate(Map args = [:]) {
  // Left for backwards compatibility
  args
}

def wrapProperties(providedProperties = []) {
  def isPRBuild = !!env.CHANGE_ID
  if (isPRBuild) {
    // Mark a special deploy status as pending, to indicate as soon as possible,
    // that this project now uses branch deploys and shouldn't be merged without
    // deploying.
    pullRequest.createStatus(
      status: 'pending',
      context: Github.deployStatusContext,
      description: 'The PR shouldn\'t be merged before it\'s deployed.',
      targetUrl: "${BUILD_URL}/console".toString()
    )
  }

  def isDeploy = Deployer.isDeploy(this)

  def tags = [
    "is_deploy=${isDeploy}",
    // Remove PR number or branch name suffix from the job name
    "project=${JOB_NAME.replaceFirst(/\/[^\/]+$/, '')}"
  ]

  if (isDeploy) {
    Deployer.validateTriggerArgs(this)

    tags.add("github_user=${Deployer.deployingUser(this)}")

    // Stop all previous builds that are still in progress
    while(currentBuild.rawBuild.getPreviousBuildInProgress() != null) {
      echo("Stopping ${currentBuild.rawBuild.getPreviousBuildInProgress()?.getAbsoluteUrl()}")
      currentBuild.rawBuild.getPreviousBuildInProgress()?.doStop()
      // Give the job some time to finish before trying again
      sleep(time: 10, unit: 'SECONDS')
    }
  }

  providedProperties + [
    pipelineTriggers([issueCommentTrigger(Deployer.triggerPattern)]),
    [
      $class: 'DatadogJobProperty',
      tagProperties: tags.join("\n")
    ]
  ]
}

def deployOnCommentTrigger(Map args) {
  if (Deployer.isDeploy(this)) {
    echo("Starting deploy")
    new Deployer(this, args).deploy()
  } else {
    echo("Build not triggered by !deploy comment. Pushing image to prepare for deploy.")
    new Deployer(this, args).pushImageForNextDeploy()
  }
}

def buildImageIfDoesNotExist(Map args, Closure body) {
  if (!args || !args.name) {
    error("'name' needs to be specified for buildImageIfDoesNotExist")
  }

  Deployer.buildImageIfDoesNotExist(this, args.name, body)
}

def updateStaticAssets(Map args) {
  stash(name: 'assets', include: "${args.assetsFolder}/**/*")
  if (args.integritiesFile) {
    stash(name: 'integrities', includes: args.integritiesFile)
  }

  withCredentials([
    string(variable: 'assumer', credentialsId: 'asset-publisher-assumer-iam-role'),
    string(variable: 'publisher', credentialsId: 'asset-publisher-iam-role')
  ]) {
    inPod(
      containers: [interactiveContainer(name: 'toolbox', image: 'salemove/jenkins-toolbox:2be721c')],
      annotations: [podAnnotation(key: 'iam.amazonaws.com/role', value: assumer)]
    ) {
      def releaseProjectSubdir = '__release'
      checkout([
        $class: 'GitSCM',
        branches: [[name: 'master']],
        userRemoteConfigs: [[
          url: 'git@github.com:salemove/release.git',
          credentialsId: scm.userRemoteConfigs[0].credentialsId
        ]],
        extensions: [
          [$class: 'RelativeTargetDirectory', relativeTargetDir: releaseProjectSubdir],
          [$class: 'CloneOption', noTags: true, shallow: true]
        ]
      ])

      container('toolbox') {
        stage('Publish assets to S3') {
          unstash('assets')

          withEnv([
            'S3_BUCKET=libs.salemove.com',
            "DIST=${args.assetsFolder}"
          ]) {
            sh("with-role ${publisher} ${releaseProjectSubdir}/publish_static_release")
          }
        }
        stage ('Deploy to Acceptance Environment') {
          def script = "${releaseProjectSubdir}/update_static_asset_versions"
          if (args.integritiesFile) {
            unstash('integrities')
            sh("${script} ${args.assetVersions} ${args.integritiesFile}")
          } else {
            sh("${script} ${args.assetVersions}")
          }
        }
      }
    }
  }
}
