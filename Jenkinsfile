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
               fortifyTranslate addJVMOptions: '', buildID: '', debug: true, excludeList: '', logFile: '', maxHeap: '', projectScanType: fortifyAdvanced(advOptions: '\'"-source" "1.8" "-cp" "n/target/fortify.jar" "fortify-plugin/src/**/*.java"\''), verbose: true
            }
        }
        stage('scan') {
            steps {
              fortifyScan addJVMOptions: '', addOptions: '', buildID: 'app', customRulepacks: '', debug: true, logFile: 'scan.log', maxHeap: '', resultsFile: 'app.fpr', verbose: true
            }
        }
    }
}
