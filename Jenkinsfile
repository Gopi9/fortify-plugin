pipeline {
    agent any
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
               projectScanType: fortifyAdvanced(advOptions: '\'"-source" "1.8" "-cp" "WEB-INF/lib/*.jar" "./**/*.java"\''), verbose: true
            }
        }
        stage('scan') {
            steps {
              fortifyScan addJVMOptions: '', addOptions: '', buildID: 'app', customRulepacks: '', debug: true, logFile: 'scan.log', maxHeap: '', resultsFile: 'app.fpr', verbose: true
            }
        }
    }
}
