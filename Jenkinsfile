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
              fortifyTranslate addJVMOptions: '', buildID: 'app', debug: true, excludeList: '', logFile: 'translation.log', maxHeap: '', projectScanType: fortifyJava(javaAddOptions: '', javaClasspath: '', javaSrcFiles: './**/*.java', javaVersion: '1.8'), verbose: true
            }
        }
        stage('scan') {
            steps {
              fortifyScan addJVMOptions: '', addOptions: '', buildID: 'app', customRulepacks: '', debug: true, logFile: 'scan.log', maxHeap: '', resultsFile: 'app.fpr', verbose: true
            }
        }
    }
}
