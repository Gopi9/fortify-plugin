
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
		stage('Run Tests') {
            parallel {
                stage('clean') {
                    steps {
			    fortifyClean addJVMOptions: '', buildID: '"${BUILDID}"', logFile: '', maxHeap: ''
                    }
                }
                stage('translation') {
                    steps {
	                    script {
                            path = pwd()
			    echo "${BUILDID}"
			    fortifyTranslate addJVMOptions: '', buildID: '"${BUILDID}"', debug: true, excludeList: '', logFile: '', maxHeap: '', projectScanType: fortifyJava(javaAddOptions: '', javaClasspath: '', javaSrcFiles: './**/*.java', javaVersion: '"${JDK_1_8}"'), verbose: true
                            //fortifyTranslate addJVMOptions: '', buildID: '${env.BUILDID}', debug: true, excludeList: '', logFile: '', maxHeap: '', projectScanType: fortifyOther(otherIncludesList: '"./**/*"', otherOptions: '"./**/.jar"'), verbose: true
	                    }
	                }
                }
			}
        }			
        stage('scan') {
            steps {
              fortifyScan addJVMOptions: '', addOptions: '', buildID: '"${BUILDID}"', customRulepacks: '', debug: true, logFile: 'scan.log', maxHeap: '', resultsFile: 'app.fpr', verbose: true
            }
        }
    }
}
