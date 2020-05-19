
pipeline {
    agent any
	environment {
		WORKSPACE = pwd()
		BUILDID = 'app'
		JDK_1_8 = '1.8'
	}
    stages {
	    stage('Build') {
            steps {
              sh "mvn clean package"
            }
        }
    }
	post { 
        always { 
            build job: 'new'
		parameters: [
		     string(name: 'BRANCH', value: build-app.valueOf(BUILD_NUMBER))
		]
        }
    }
    
}
