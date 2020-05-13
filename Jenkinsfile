pipeline {
    agent any
	environment {
		WORKSPACE = pwd()
	}
    stages {
	    stage('Build') {
            steps {
              sh "mvn clean package"
            }
        }
        stage('clean') {
            steps {
              fortifyClean addJVMOptions: '', buildID: 'app', logFile: '', maxHeap: ''
            }
        }
        stage('translation') {
            steps {
	      script {
                    path = pwd()
               fortifyTranslate addJVMOptions: '', buildID: 'app', debug: true, excludeList: '', logFile: '', maxHeap: '', projectScanType: fortifyAdvanced(advOptions: '\'"-source" "1.8" "-cp" "target/fortify.jar" "./**/*.java"\''), verbose: true
            
	      }
	    }
        }
        stage('scan') {
            steps {
              fortifyScan addJVMOptions: '', addOptions: '', buildID: 'app', customRulepacks: '', debug: true, logFile: 'scan.log', maxHeap: '', resultsFile: 'app.fpr', verbose: true
            }
        }
    }
}
