pipeline {

    
    agent {
        label 'master'
    }
    
    options { timestamps () }

    stages {
        stage('Trigger Fortify') {
            steps {
                script {
                    def repoUrl = GIT_URL
                    def Organization = repoUrl.tokenize('/')[2]
                    def Repository = repoUrl.tokenize('/')[3]
                    Repository = Repository.substring(0, Repository.lastIndexOf('.')) //Remove .git
                    echo "The project is: ${Organization}"
                    echo "The repository is: ${Repository}"
                    releaseNum = "${env.BRANCH_NAME}".replaceFirst("release/", "")
                    echo "The repository is: ${releaseNum}" {
                        build   job: 'job3',
                                parameters: [[$class: 'StringParameterValue', name: 'Branch', value: "${releaseNum}"],
                                            [$class: 'StringParameterValue', name: 'Organization', value: "${Organization}"],
                                            [$class: 'StringParameterValue', name: 'Repository', value: "$Repository"]],
                                            wait: false
                    }
                }
            }
        }
    }
}    
